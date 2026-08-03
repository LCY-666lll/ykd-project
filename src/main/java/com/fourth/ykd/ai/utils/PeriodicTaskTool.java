package com.fourth.ykd.ai.utils;

import com.fourth.ykd.ai.dto.PersistedChatMessage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.infrastructure.memory.TaskMemoryRepository;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用周期任务工具（SQLite 持久化）。
 *
 * 不预设任务类型，创建时由 AI 将用户自然语言解析为 cron 表达式和任务名；
 * 执行时 AI 携带全套工具自行决策，支持新闻、天气、问候、提醒等任意场景。
 * 任务数据持久化到 task_memory 表，重启后自动恢复。
 */
@Slf4j
@Component
public class PeriodicTaskTool {

    // 从配置文件读取 iLink 用户 ID（${ilink.user-id}），用于微信消息推送
    @Value("${ilink.user-id}")
    private String ilinkUserId;

    private final ChatClient springAiChatClient;
    private final BaiduSearchTool baiduSearchTool;
    private final TimeTool timeTool;
    private final WeatherTool weatherTool;
    private final MathCalculatorTool mathCalculatorTool;
    private final TranslationTool translationTool;
    private final SqliteChatMessageRepository sqliteChatMessageRepository;
    private final TaskScheduler taskScheduler;
    private final IlinkClientManager ilinkClientManager;
    private final TaskMemoryRepository taskMemoryRepository;

    private final Map<Long, TaskEntry> tasks = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public PeriodicTaskTool(
            ChatClient springAiChatClient,
            BaiduSearchTool baiduSearchTool,
            TimeTool timeTool,
            WeatherTool weatherTool,
            MathCalculatorTool mathCalculatorTool,
            TranslationTool translationTool,
            SqliteChatMessageRepository sqliteChatMessageRepository,
            TaskScheduler taskScheduler,
            IlinkClientManager ilinkClientManager,
            TaskMemoryRepository taskMemoryRepository
    ) {
        this.springAiChatClient = springAiChatClient;
        this.baiduSearchTool = baiduSearchTool;
        this.timeTool = timeTool;
        this.weatherTool = weatherTool;
        this.mathCalculatorTool = mathCalculatorTool;
        this.translationTool = translationTool;
        this.sqliteChatMessageRepository = sqliteChatMessageRepository;
        this.taskScheduler = taskScheduler;
        this.ilinkClientManager = ilinkClientManager;
        this.taskMemoryRepository = taskMemoryRepository;
    }

    // ==================== 启动恢复 ====================

    /** 应用启动时自动从 task_memory 表加载周期任务并恢复调度。 */
    @PostConstruct
    private void loadTasks() {
        // 从数据库查询所有待执行的周期任务（task_type='PERIODIC'（周期） 且 status='PENDING'（待执行））
        List<TaskMemoryRepository.TaskRow> pending = taskMemoryRepository.findPendingByType("PERIODIC");
        // 如果没有待执行的任务，直接返回
        if (pending.isEmpty()) return;
        // 创建列表用于存储加载的任务条目
        List<TaskEntry> loaded = new ArrayList<>();
        // 遍历数据库中的每一行任务记录
        for (TaskMemoryRepository.TaskRow row : pending) {
            // 将数据库行转换为内存中的TaskEntry对象
            TaskEntry entry = new TaskEntry(row.id(), row.taskName(), row.cronExpression(),
                    row.taskDescription(), parseLastExecuted(row.lastExecutedAt()));
            // 将任务条目放入内存注册表
            tasks.put(row.id(), entry);
            // 添加到加载列表（用于后续批量调度）
            loaded.add(entry);
        }
        // 记录恢复日志：显示恢复了多少个周期任务
        log.info("[周期任务][恢复] 从 task_memory 恢复了 {} 个周期任务", loaded.size());
        // 创建新线程等待 iLink 登录完成后恢复调度
        new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                // 等待 500 毫秒。如果线程被中断（如应用关闭），退出循环
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
                // 检查 iLink 客户端是否已登录
                // 获取 iLink 客户端
                if (ilinkClientManager.findClient()
                        // 检查登录状态
                        .map(com.github.wechat.ilink.sdk.ILinkClient::isLoggedIn)
                        // 如果客户端不存在，默认 false
                        .orElse(false)) {
                    // 已登录，退出等待循环
                    break;
                }
            }
            loaded.forEach(this::schedule);
            log.info("[周期任务][恢复] 已启动 {} 个周期任务", loaded.size());
        }, "periodic-recovery").start();
    }


    /**
     * 创建周期任务 — AI 解析 cron + 任务名（合并为 1 次调用），存入内存并调度。
     *
     * 执行流程：
     *       ->调用 AI 解析用户请求，提取 cron 表达式和任务名
     *       ->将任务数据持久化到 SQLite task_memory 表
     *       ->将任务条目放入内存注册表
     *       ->注册 Spring cron 调度
     *       ->返回创建成功消息给用户
     */
    @Tool(name = "create_periodic_task", description = """
            创建重复执行的周期任务（基于cron表达式，如每天/每小时/每周重复执行）。
            与 schedule_task 的区别：schedule_task 是一次性延迟任务，create_periodic_task 是重复执行任务。
            当用户要求"每天""每小时""每隔X分钟""每周"等周期重复执行时调用此工具。
            例如：'每天早上8点给我发科技新闻'、'每30分钟提醒我喝水'、'每周一早上9点提醒写周报'。
            会自动解析用户的时间频率描述为cron表达式，并调度执行。
            创建成功后返回任务详情（任务名、执行规则、任务ID等）。
            """)
    public String create(
            @ToolParam(description = "用户的完整需求描述，包含时间频率和任务内容", required = true)
            String userRequest) {
        log.info("[周期任务][创建] req={}", userRequest);

        // 1.一次 AI 调用同时解析 cron 和提取任务名
        TaskParseResult parsed = parseTask(userRequest);
        if (parsed == null || !StringUtils.hasText(parsed.cron())) {
            return "抱歉，我没能理解您想要的执行时间。请说得更明确一些，"
                    + "例如「每天早上8点」「每30分钟」「每晚20:30」。";
        }
        // 提取任务名：如果 AI 解析出了任务名则使用，否则用用户请求的前20个字符作为任务名
        String taskName = StringUtils.hasText(parsed.taskName())
                ? parsed.taskName().trim() : userRequest.length() > 20 ? userRequest.substring(0, 20) + "…" : userRequest;

        log.info("[周期任务][创建][解析完成] name={}, cron={}", taskName, parsed.cron());

        // 2.持久化到数据库并获取 ID
        // 向 task_memory 表插入一条 PERIODIC 类型的任务记录
        long taskId = taskMemoryRepository.insert(
                "PERIODIC",
                taskName,
                ilinkUserId,
                parsed.cron(),
                userRequest.trim(),
                null
        );
        // 创建内存中的任务条目
        TaskEntry entry = new TaskEntry(
                taskId,
                taskName,
                parsed.cron(),
                userRequest.trim(),
                null
        );
        // 将任务条目放入内存注册表
        tasks.put(taskId, entry);

        // 3.动态调度：将任务注册到 Spring TaskScheduler，按 cron 表达式自动触发
        schedule(entry);

        // 4.构建返回消息
        String desc = describeCron(parsed.cron());
        String result = String.format(
                "✅ 已创建周期任务「%s」\n"
                + "⏰ 执行规则：%s\n"
                + "📋 任务内容：%s\n"
                + "🆔 任务ID：%d\n\n"
                + "任务到期时我会自动执行并通过微信推送给您。\n"
                + "💡 提示：任务已持久化，重启后自动恢复。",
                taskName, desc, userRequest.trim(), taskId
        );
        log.info("[周期任务][创建][成功] taskId={}", taskId);
        return result;
    }


    /** 查询周期任务：列出所有周期任务。 */
    @Tool(name = "list_periodic_tasks", description = """
            查看/列出用户已创建的所有重复执行的周期任务。
            当用户询问'我有哪些周期任务''查看周期任务''周期任务列表''查询周期任务'时调用。
            返回每个任务的任务名、任务内容、最近执行时间和任务ID。
            """)
    public String list() {
        // 从数据库查询所有待执行的周期任务
        List<TaskMemoryRepository.TaskRow> rows = taskMemoryRepository.findPendingByType("PERIODIC");
        if (rows.isEmpty()) {
            return "您当前没有设置任何周期任务。\n\n"
                    + "💡 您可以对我说：\n"
                    + "  • 「每天早上8点给我发科技新闻」\n"
                    + "  • 「每30分钟给我发一条励志名言」\n"
                    + "  • 「每天晚上20:00推送当天天气」\n"
                    + "  • 「每周一早上9点提醒我写周报」\n\n"
                    + "💡 提示：任务已持久化到数据库，重启后自动恢复。";
        }
        // 使用 StringBuilder 高效构建列表文本
        StringBuilder sb = new StringBuilder("📋 您的周期任务（共 ");
        sb.append(rows.size()).append(" 个）：\n\n");
        int idx = 1;  //序号计数器，从 1 开始
        for (TaskMemoryRepository.TaskRow r : rows) {
            String lastRun = r.lastExecutedAt() != null ? r.lastExecutedAt() : "尚未执行";
            sb.append(String.format(
                            "查看周期任务：\n"
                            +"%d. %s\n"
                            +"任务内容：%s\n"
                            +"最近执行：%s\n"
                            +"任务ID：%d\n\n",
                    idx, r.taskName(),r.taskDescription(),lastRun,r.id()
                    )
            );
            // 如果不是最后一个任务，添加空行分隔
            if (idx < rows.size()) sb.append("\n");
            idx++;
        }
        sb.append("---\n💡 删除任务请说「删除 + 任务ID」。");
        return sb.toString();
    }


    /** 删除周期任务。 */
    @Tool(name = "delete_periodic_task", description = """
            删除/取消指定的周期任务。
            当用户说'删除周期任务''取消每天早上8点的新闻''停止某个周期任务'时调用。
            可通过任务ID或任务名称来指定要删除的任务。
            """)
    public String delete(
            @ToolParam(description = "要删除的任务名称或任务ID（数字）", required = true)
            String taskIdentifier) {
        TaskEntry task = matchTask(taskIdentifier);
        if (task == null) {
            return "未找到匹配「" + taskIdentifier + "」的周期任务。\n"
                    + "您可以说「查看我的周期任务」确认后再删。";
        }
        // 取消 Spring 调度（从调度器中移除）
        cancel(task.id());
        // 从内存注册表中移除任务条目
        tasks.remove(task.id());
        // 将数据库中的任务状态更新为 CANCELLED（取消）
        taskMemoryRepository.updateStatus(task.id(), "CANCELLED");
        return "✅ 已删除周期任务「" + task.taskName() + "」。";
    }


    /** 立即手动执行周期任务。 */
    @Tool(name = "execute_periodic_task_now", description = """
            立即手动执行一个周期任务，不等其cron调度时间。
            当用户说'立即执行周期任务''马上执行每天早上8点的新闻''提前执行'时调用。
            可通过任务ID或任务名称来指定要执行的任务。
            执行结果会推送到用户微信。
            """)
    public String executeNow(
            @ToolParam(description = "要立即执行的任务名称或任务ID（数字）", required = true)
            String taskIdentifier) {
        TaskEntry task = matchTask(taskIdentifier);
        if (task == null) {
            return "未找到匹配「" + taskIdentifier + "」的周期任务。";
        }
        try {
            String content = execute(task);
            return "✅ 已手动执行「" + task.taskName() + "」，结果已推送到微信并写入聊天记忆：\n\n" + content;
        } catch (Exception e) {
            log.warn("[周期任务][手动执行][失败] taskId={}, reason={}", task.id(), e.getMessage());
            return "❌ 执行「" + task.taskName() + "」时出错：" + e.getMessage();
        }
    }

    /**
     * 调用 LLM 执行周期任务，将结果推送到微信并写入聊天记忆。
     * 执行流程：
     *       获取当前时间
     *     ->调用 LLM根据任务描述生成回答
     *     ->通过 iLink 将结果推送到用户微信
     *     ->将执行结果写入 SQLite 聊天记忆
     *     ->更新任务的 lastExecutedAt 时间戳
     */
    private String execute(TaskEntry task) {
        log.info("[周期任务][执行][开始] taskId={}, name={}", task.id(), task.taskName());
        // 获取当前时间信息
        String now = timeTool.getTimeInfo("now", null);
        // 使用 ChatClient 构建 Prompt 并调用 LLM
        String answer = springAiChatClient.prompt()
                .system("""
                        你是一个周期任务执行器。用户设置了以下定时需求，
                        请根据当前时间和用户需求，调用合适的工具完成。
                        可用工具：
                        - search_realtime_information：搜索实时新闻/信息
                        - query_current_weather / query_weather_forecast：查询天气
                        - get_time_info：获取当前日期时间
                        - calculate_math_expression：数学计算
                        - translate_text：翻译

                        规则：
                        - 必须基于工具调用结果回答，不得编造
                        - 工具调用失败时如实告知
                        - 回答简洁友好，适合作为推送消息
                        - 在回答开头注明这是周期任务的自动执行结果
                        """)
                .user("当前时间：%s\n\n用户设置的周期任务需求：\n%s".formatted(now, task.prompt()))
                .tools(baiduSearchTool, timeTool, weatherTool, mathCalculatorTool, translationTool)
                .call()
                .content();
        // 如果 LLM 返回的答案为空或仅包含空白字符，使用默认回复
        String finalAnswer = (answer != null && !answer.isBlank())
                ? answer
                : "任务执行完成，但未能生成有效内容。";

        // 2.iLink 推送到微信（需用户发过消息建立 context token）
        ilinkClientManager.findClient().ifPresent(client -> {
            if (!client.isLoggedIn()) {
                log.info("[周期任务][推送][跳过] taskId={}, iLink未登录，结果已保存至聊天记忆", task.id());
                return;
            }
            try {
                client.sendText(ilinkUserId, finalAnswer);
                log.info("[周期任务][推送][成功] taskId={}", task.id());
            } catch (Exception e) {
                log.info("[周期任务][推送][跳过] taskId={}, reason={}, 等待用户发消息后自动恢复",
                        task.id(), e.getMessage());
            }
        });

        // 3.写入聊天记忆
        // 构建记忆内容（包含任务名、执行时间、需求和结果）
        String memory = """
                【周期任务自动执行】
                任务：%s
                时间：%s
                需求：%s

                ————
                %s
                """.formatted(task.taskName(), now, task.prompt(), finalAnswer);
        // 将执行结果保存到聊天记忆（角色为 ASSISTANT(助手)）
        sqliteChatMessageRepository.save(ilinkUserId, PersistedChatMessage.Role.ASSISTANT, memory);
        // 软删除旧消息，保留最近 100 条（防止聊天记忆无限增长）
        sqliteChatMessageRepository.softDeleteOldMessages(ilinkUserId, 100);

        // 4.更新上次执行时间
        // 设置内存中的上次执行时间为当前时间
        task.lastExecutedAt = LocalDateTime.now();
        // 将上次执行时间持久化到数据库
        taskMemoryRepository.updateLastExecuted(
                task.id(),
                task.lastExecutedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        log.info("[周期任务][执行][成功] taskId={}", task.id());
        return finalAnswer;
    }

    // ==================== AI 解析（合并为 1 次调用） ====================
    /**
     * 一次 AI 调用同时解析 cron 表达式和提取任务名。
     * 通过 System Prompt 引导 LLM 输出严格的 JSON 格式，然后反序列化为 TaskParseResult 对象。
     */
    private TaskParseResult parseTask(String userRequest) {
        String now = timeTool.getTimeInfo("now", null);
        return springAiChatClient.prompt()
                .system("""
                        你是周期任务解析器。根据用户自然语言输出JSON：
                        {"cron":"秒 分 时 日 月 周","taskName":"简短任务名"}

                        cron 规则：
                        - 秒位固定为 0
                        - 每天X点 → "0 0 X * * ?"
                        - 每天X点Y分 → "0 Y X * * ?"
                        - 每N分钟 → "0 0/N * * * ?"
                        - 每N小时 → "0 0 0/N * * ?"
                        - 每周X HH:MM → "0 MM HH * * DAY"
                        - "晚上8点"小时为 20，"下午3点"小时为 15
                        - 没指定分钟时默认为 0

                        taskName 规则：
                        - 不超过10个字，如"早间新闻""定时问候""晚间天气"
                        - 只输出JSON，不解释
                        """.formatted(now))
                .user(userRequest.trim())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, ilinkUserId))
                .call()
                .entity(TaskParseResult.class);
    }

    // ==================== 调度管理 ====================
    /** 将任务注册到 Spring TaskScheduler，按 cron 表达式自动触发。 */
    private void schedule(TaskEntry task) {
        try {
            // 根据cron表达式创建CronTrigger触发器
            CronTrigger trigger = new CronTrigger(task.cronExpression());
            // 将任务注册到 Spring TaskScheduler
            // schedule() 返回 ScheduledFuture 句柄，用于后续取消任务
            ScheduledFuture<?> f = taskScheduler.schedule(
                    () -> executeSafely(task),
                    trigger
            );
            // 将 ScheduledFuture 句柄存入 futures 表
            futures.put(task.id(), f);
            log.info("[周期任务][调度] taskId={}, cron={}", task.id(), task.cronExpression());
        } catch (Exception e) {
            log.warn("[周期任务][调度][失败] taskId={}, cron={}, reason={}",
                    task.id(), task.cronExpression(), e.getMessage());
        }
    }
    /** 取消指定任务的调度。 */
    private void cancel(long taskId) {
        // 从 futures 表中移除并获取 ScheduledFuture 句柄
        ScheduledFuture<?> f = futures.remove(taskId);
        // 如果句柄存在（任务已被调度），取消调度
        if (f != null) {
            f.cancel(false);
        }
    }
    /**
     * 安全执行任务：包含异常处理和任务存在性检查。
     * 这是 cron 触发器调用的回调方法。
     * Spring 的 TaskScheduler 在到达执行时间时会自动调用此方法。
     */
    private void executeSafely(TaskEntry task) {
        try {
            // 检查任务是否仍在内存注册表中（可能已被用户删除）
            if (!tasks.containsKey(task.id())) {
                // 任务已被删除，取消调度
                cancel(task.id());
                return;
            }
            // 从内存注册表中获取最新的任务条目并执行
            // 使用 tasks.get(task.id()) 而非直接使用 task 参数，确保获取的是最新的任务状态
            execute(tasks.get(task.id()));
        } catch (Exception e) {
            log.warn("[周期任务][执行][异常] taskId={}, reason={}", task.id(), e.getMessage());
        }
    }

    // ==================== 内存查询 ====================
    /** 通过标识符匹配任务：先尝试按 ID 查找，再尝试按名称模糊匹配。 */
    private TaskEntry matchTask(String identifier) {
        try {
            long id = Long.parseLong(identifier.trim());
            return tasks.get(id);
        } catch (NumberFormatException ignored) {}
        // 遍历所有任务，按名称模糊匹配
        for (TaskEntry t : tasks.values()) {
            if (t.taskName() != null && t.taskName().contains(identifier.trim())){
                return t;
            }
        }
        return null;
    }

    // ==================== 辅助方法 ====================
    /** 解析数据库中的上次执行时间字符串为 LocalDateTime 对象。 */
    private LocalDateTime parseLastExecuted(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalDateTime.parse(
                    time,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            );
        } catch (Exception e) {
            return null;
        }
    }
    /** 将 cron 表达式转换为人类可读的描述文本。 */
    private String describeCron(String cron) {
        if (cron == null || cron.isBlank()) return "按设定时间";
        String[] p = cron.trim().split("\\s+");
        if (p.length < 6) return "cron(" + cron + ")";

        String min = p[1], hour = p[2], dayOfWeek = p[5];

        if (min.contains("/")) {
            return "每" + min.split("/")[1] + "分钟";
        }
        if (hour.contains("/") && "0".equals(min)) {
            return "每" + hour.split("/")[1] + "小时";
        }

        StringBuilder sb = new StringBuilder();
        if (!"?".equals(dayOfWeek) && !"*".equals(dayOfWeek)) {
            sb.append("每周").append(weekName(dayOfWeek));
        } else {
            sb.append("每天");
        }
        sb.append(String.format(" %s:%s", hour, min.length() == 1 ? "0" + min : min));
        return sb.toString();
    }
    /** 将 cron 表达式中的星期字段转换为中文星期名称。 */
    private String weekName(String d) {
        return switch (d) {
            case "1","MON" -> "一";
            case "2","TUE" -> "二";
            case "3","WED" -> "三";
            case "4","THU" -> "四";
            case "5","FRI" -> "五";
            case "6","SAT" -> "六";
            case "7","0","SUN" -> "日";
            default -> d;
        };
    }

    // ==================== 内部类型 ====================

    /** AI 解析结果。包含 cron 表达式和任务名。 */
    public record TaskParseResult(String cron, String taskName) {}

    /**
     * 内存中的任务条目:封装周期任务的所有信息。
     * 任务 ID，任务名称，cron 表达式，任务提示词，上次执行时间
     */
    static class TaskEntry {
        private final long id;
        private final String taskName;
        private final String cronExpression;
        private final String prompt;
        volatile LocalDateTime lastExecutedAt;

        TaskEntry(long id, String taskName, String cronExpression,
                  String prompt, LocalDateTime lastExecutedAt) {
            this.id = id;
            this.taskName = taskName;
            this.cronExpression = cronExpression;
            this.prompt = prompt;
            this.lastExecutedAt = lastExecutedAt;
        }
        // 私有的 getter 方法（不使用 Lombok，手动定义）
        long id() { return id; }
        String taskName() { return taskName; }
        String cronExpression() { return cronExpression; }
        String prompt() { return prompt; }
    }

}
