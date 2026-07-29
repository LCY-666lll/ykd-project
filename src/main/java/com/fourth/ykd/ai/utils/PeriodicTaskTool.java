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
 * <p>不预设任务类型，创建时由 AI 将用户自然语言解析为 cron 表达式和任务名；
 * 执行时 AI 携带全套工具自行决策，支持新闻、天气、问候、提醒等任意场景。
 * 任务数据持久化到 task_memory 表，重启后自动恢复。</p>
 */
@Slf4j
@Component
public class PeriodicTaskTool {

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

    /** 从 task_memory 表加载周期任务并恢复调度。 */
    @PostConstruct
    private void loadTasks() {
        List<TaskMemoryRepository.TaskRow> pending = taskMemoryRepository.findPendingByType("PERIODIC");
        if (pending.isEmpty()) return;
        List<TaskEntry> loaded = new ArrayList<>();
        for (TaskMemoryRepository.TaskRow row : pending) {
            TaskEntry entry = new TaskEntry(row.id(), row.taskName(), row.cronExpression(),
                    row.taskDescription(), parseLastExecuted(row.lastExecutedAt()));
            tasks.put(row.id(), entry);
            loaded.add(entry);
        }
        log.info("[周期任务][恢复] 从 task_memory 恢复了 {} 个周期任务", loaded.size());
        new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (ilinkClientManager.findClient()
                        .map(com.github.wechat.ilink.sdk.ILinkClient::isLoggedIn)
                        .orElse(false)) {
                    break;
                }
            }
            loaded.forEach(this::schedule);
            log.info("[周期任务][恢复] 已启动 {} 个周期任务", loaded.size());
        }, "periodic-recovery").start();
    }

    // ==================== 公开方法 ====================

    /** 创建周期任务 — AI 解析 cron + 任务名（合并为 1 次调用），存入内存并调度。 */
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

        // 1. 一次 AI 调用同时解析 cron 和提取任务名
        TaskParseResult parsed = parseTask(userRequest);
        if (parsed == null || !StringUtils.hasText(parsed.cron())) {
            return "抱歉，我没能理解您想要的执行时间。请说得更明确一些，"
                    + "例如「每天早上8点」「每30分钟」「每晚20:30」。";
        }
        String taskName = StringUtils.hasText(parsed.taskName())
                ? parsed.taskName().trim() : userRequest.length() > 20 ? userRequest.substring(0, 20) + "…" : userRequest;

        log.info("[周期任务][创建][解析完成] name={}, cron={}", taskName, parsed.cron());

        // 3. 持久化到数据库并获取 ID
        long taskId = taskMemoryRepository.insert(
                "PERIODIC", taskName, ilinkUserId, parsed.cron(), userRequest.trim(), null);
        TaskEntry entry = new TaskEntry(taskId, taskName, parsed.cron(),
                userRequest.trim(), null);
        tasks.put(taskId, entry);

        // 4. 动态调度
        schedule(entry);

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

    /** 列出所有周期任务。 */
    @Tool(name = "list_periodic_tasks", description = """
            查看/列出用户已创建的所有重复执行的周期任务。
            当用户询问'我有哪些周期任务''查看周期任务''周期任务列表'时调用。
            返回每个任务的任务名、执行规则（cron描述）、任务内容、最近执行时间和任务ID。
            """)
    public String list() {
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
        StringBuilder sb = new StringBuilder("📋 您的周期任务（共 ");
        sb.append(rows.size()).append(" 个）：\n\n");
        int idx = 1;
        for (TaskMemoryRepository.TaskRow r : rows) {
            String lastRun = r.lastExecutedAt() != null ? r.lastExecutedAt() : "尚未执行";
            sb.append(String.format("%d. %s\n", idx, r.taskName()));
            sb.append(String.format("   ⏰ 执行规则：%s\n", describeCron(r.cronExpression())));
            sb.append(String.format("   📝 任务内容：%s\n", r.taskDescription()));
            sb.append(String.format("   🕐 最近执行：%s\n", lastRun));
            sb.append(String.format("   🆔 任务ID：%d\n", r.id()));
            if (idx < rows.size()) sb.append("\n");
            idx++;
        }
        sb.append("---\n💡 删除任务请说「删除 + 任务名称或ID」。");
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
        cancel(task.id());
        tasks.remove(task.id());
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

    // ==================== 核心执行 ====================

    private String execute(TaskEntry task) {
        log.info("[周期任务][执行][开始] taskId={}, name={}", task.id(), task.taskName());

        String now = timeTool.getTimeInfo("now", null);

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

        String finalAnswer = (answer != null && !answer.isBlank())
                ? answer
                : "任务执行完成，但未能生成有效内容。";

        // iLink 推送到微信（需用户发过消息建立 context token）
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

        // 写入聊天记忆
        String memory = """
                【周期任务自动执行】
                任务：%s
                时间：%s
                需求：%s

                ————
                %s
                """.formatted(task.taskName(), now, task.prompt(), finalAnswer);
        sqliteChatMessageRepository.save(ilinkUserId, PersistedChatMessage.Role.ASSISTANT, memory);
        sqliteChatMessageRepository.softDeleteOldMessages(ilinkUserId, 100);

        task.lastExecutedAt = LocalDateTime.now();
        taskMemoryRepository.updateLastExecuted(task.id(),
                task.lastExecutedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        log.info("[周期任务][执行][成功] taskId={}", task.id());
        return finalAnswer;
    }

    // ==================== AI 解析（合并为 1 次调用） ====================

    /** 一次 AI 调用同时解析 cron 表达式和提取任务名。 */
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

    private void schedule(TaskEntry task) {
        try {
            CronTrigger trigger = new CronTrigger(task.cronExpression());
            ScheduledFuture<?> f = taskScheduler.schedule(
                    () -> executeSafely(task), trigger);
            futures.put(task.id(), f);
            log.info("[周期任务][调度] taskId={}, cron={}", task.id(), task.cronExpression());
        } catch (Exception e) {
            log.warn("[周期任务][调度][失败] taskId={}, cron={}, reason={}",
                    task.id(), task.cronExpression(), e.getMessage());
        }
    }

    private void cancel(long taskId) {
        ScheduledFuture<?> f = futures.remove(taskId);
        if (f != null) { f.cancel(false); }
    }

    private void executeSafely(TaskEntry task) {
        try {
            if (!tasks.containsKey(task.id())) {
                cancel(task.id());
                return;
            }
            execute(tasks.get(task.id()));
        } catch (Exception e) {
            log.warn("[周期任务][执行][异常] taskId={}, reason={}", task.id(), e.getMessage());
        }
    }

    // ==================== 内存查询 ====================

    private TaskEntry matchTask(String identifier) {
        try {
            long id = Long.parseLong(identifier.trim());
            return tasks.get(id);
        } catch (NumberFormatException ignored) {}
        for (TaskEntry t : tasks.values()) {
            if (t.taskName() != null && t.taskName().contains(identifier.trim())) return t;
        }
        return null;
    }

    // ==================== 辅助方法 ====================

    private LocalDateTime parseLastExecuted(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

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

    private String weekName(String d) {
        return switch (d) {
            case "1","MON" -> "一"; case "2","TUE" -> "二";
            case "3","WED" -> "三"; case "4","THU" -> "四";
            case "5","FRI" -> "五"; case "6","SAT" -> "六";
            case "7","0","SUN" -> "日"; default -> d;
        };
    }

    // ==================== 内部类型 ====================

    /** AI 解析结果。 */
    public record TaskParseResult(String cron, String taskName) {}

    /** 内存中的任务条目。 */
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

        long id() { return id; }
        String taskName() { return taskName; }
        String cronExpression() { return cronExpression; }
        String prompt() { return prompt; }
    }

}
