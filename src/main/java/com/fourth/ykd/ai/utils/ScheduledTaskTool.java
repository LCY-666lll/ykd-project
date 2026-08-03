package com.fourth.ykd.ai.utils;

import com.fourth.ykd.ai.infrastructure.memory.TaskMemoryRepository;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 定时任务工具：创建和管理一次性延迟执行的定时任务，支持延迟执行、SQLite 持久化、取消任务。
 *
 * 技术实现：
 *       ->使用 ScheduledExecutorService（2 个守护线程）管理延迟任务
 *       ->任务数据持久化到 SQLite task_memory 表，重启后自动恢复
 *       ->任务到期时通过 AiChatService 调用 LLM 执行任务内容
 *       ->执行结果通过 iLink 推送到用户微信
 *
 * 用户上下文传递：通过 ThreadLocal 存储当前用户 ID，因为 AI 工具调用时无法直接获取当前用户信息。
 */
@Slf4j
@Component
public class ScheduledTaskTool {

    // ThreadLocal 变量：存储当前线程的用户 ID
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    // 定时任务调度线程池：2 个守护线程
    // 线程名称为 "scheduled-task"，便于调试和监控
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
            2,
            r -> {
                Thread t = new Thread(r, "scheduled-task");
                t.setDaemon(true);
                return t;
            }
    );

    /** 存储任务ID对应的Future，用于取消任务 */
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    private final IlinkClientManager clientManager;
    private final AiChatService aiChatService;
    private final TaskMemoryRepository taskMemoryRepository;

    public ScheduledTaskTool(IlinkClientManager clientManager, @Lazy AiChatService aiChatService,
                             TaskMemoryRepository taskMemoryRepository) {
        this.clientManager = clientManager;
        this.aiChatService = aiChatService;
        this.taskMemoryRepository = taskMemoryRepository;
    }

    // 设置当前线程的用户 ID。
    private static void setCurrentUserId(String userId) { CURRENT_USER_ID.set(userId); }
    // 清除当前线程的用户 ID。
    private static void clearCurrentUserId() { CURRENT_USER_ID.remove(); }

    /**
     * 在用户上下文中执行操作，自动管理 CURRENT_USER_ID 的生命周期。
     *
     * 统一封装 ThreadLocal 的 set → execute → finally clear 流程，
     * 外部调用者无需手动调用 setCurrentUserId / clearCurrentUserId。
     *
     * @param userId 当前用户 ID
     * @param action 需要在用户上下文中执行的操作
     * @return 操作的返回结果
     */
    public String executeWithUserContext(String userId, java.util.function.Supplier<String> action) {
        CURRENT_USER_ID.set(userId);
        try {
            return action.get();
        } finally {
            CURRENT_USER_ID.remove();
        }
    }

    // ==================== 启动恢复 ====================

    /**
     * 从 task_memory 表加载未执行的定时任务。
     *
     * 恢复逻辑：
     *      ->查询所有 status='PENDING' 且 task_type='SCHEDULED' 的任务
     *      ->如果任务的 execute_at 时间已经过去，标记为 EXPIRED
     *      ->如果任务尚未到期，计算剩余延迟并重新调度
     */
    @PostConstruct
    private void loadTasks() {
        // 从数据库查询所有待执行的定时任务
        List<TaskMemoryRepository.TaskRow> pending = taskMemoryRepository.findPendingByType("SCHEDULED");
        long now = System.currentTimeMillis();
        int restored = 0;
        // 遍历每个待执行任务
        for (TaskMemoryRepository.TaskRow row : pending) {
            if (row.executeAt() != null && row.executeAt() <= now) {
                taskMemoryRepository.updateStatus(row.id(), "EXPIRED");
                continue;
            }
            // 计算剩余延迟秒数
            long delaySeconds = row.executeAt() != null
                    ? Math.max(1, (row.executeAt() - now) / 1000)
                    : 60;
            // 重新调度任务
            scheduleDelayed(row.id(), row.userId(), row.taskDescription(), delaySeconds);
            restored++;
        }
        if (restored > 0) {
            log.info("[定时任务][恢复] 从 task_memory 恢复了 {} 个待执行任务", restored);
        }
    }

    /** 创建定时任务 — 延迟指定秒数后自动执行。
     * 这是 AI 可调用的工具函数。当用户说"3分钟后提醒我喝水"时，
     * LLM 会解析出 taskDescription 和 delaySeconds 并调用此方法。
     */
    @Tool(name = "schedule_task", description = """
            设置定时任务，延迟指定秒数后自动执行。
            可执行任意任务：查询天气、提醒事项、搜索新闻、生成图片、翻译等。
            返回taskId，可用于取消任务。
            taskDescription：任务描述，AI会根据描述自动选择合适的工具执行。
            delaySeconds：延迟秒数，如60=1分钟，300=5分钟，1800=30分钟。
            """)
    public String scheduleTask(
            @ToolParam(description = "任务描述，如'查询北京天气'、'提醒我喝水'、'生成一张风景图'") String taskDescription,
            @ToolParam(description = "延迟秒数，如60表示1分钟后") long delaySeconds) {

        String userId = CURRENT_USER_ID.get();
        if (userId == null) return "错误：无法获取当前用户信息";
        if (delaySeconds <= 0 || delaySeconds > 86400) return "错误：延迟时间需在1秒到24小时之间";

        long executeAt = System.currentTimeMillis() + delaySeconds * 1000;

        // 1.持久化到数据库：向 task_memory 表插入一条 SCHEDULED 类型的任务记录
        long taskId = taskMemoryRepository.insert(
                "SCHEDULED",
                "定时任务",
                userId,
                null,
                taskDescription,
                executeAt
        );

        // 2.调度执行:向 ScheduledExecutorService 注册延迟任务
        scheduleDelayed(taskId, userId, taskDescription, delaySeconds);

        log.info("[定时任务][创建] taskId={}, userId={}, delay={}s, task={}", taskId, userId, delaySeconds, taskDescription);
        // 构建返回消息
        String result = String.format(
                "✅ 已创建定时任务\n"
                +"任务内容：%s\n"
                +"任务ID：%d\n"
                +"任务剩余时间：%s",
                taskDescription,taskId,formatDelay(delaySeconds)
        );

        return result;
    }

    /** 由 IlinkReplyProcessor 调用：AI 解析用户文本后直接调度，绕过 DeepSeek 工具调用。 */
    public String parseAndSchedule(String userId, String userText) {
        // 获取当前时间字符串（用于 AI 理解时间上下文）
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // 调用 AI 解析用户文本，提取 delaySeconds 和 taskDescription
        String aiResult = aiChatService.chat(userId,
                "【系统指令】根据用户文本输出严格JSON，不要解释，不要Markdown：\n"
                + "{\"delaySeconds\":<数字>,\"taskDescription\":\"<描述>\"}\n"
                + "delaySeconds为从现在到目标时间的秒数（最大86400=24小时）。\n"
                + "当前时间：" + now + "\n"
                + "用户文本：" + userText).reply();
        // 解析出的延迟秒数
        long delaySeconds;
        // 解析出的任务描述
        String taskDescription;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(aiResult);
            delaySeconds = node.get("delaySeconds").asLong();
            taskDescription = node.get("taskDescription").asText();
        } catch (Exception e) {
            log.warn("[定时任务][解析][失败] result={}, reason={}", aiResult, e.getMessage());
            return "抱歉，没能理解您设置的时间。请说得更明确一些，例如「3分钟后提醒我」或「在15:20提醒我」。";
        }

        if (delaySeconds <= 0 || delaySeconds > 86400) {
            return "错误：延迟时间需在1秒到24小时之间";
        }

        return executeWithUserContext(
                userId,
                () -> scheduleTask(taskDescription, delaySeconds)
        );
    }

    /** 取消已设置的定时任务。 */
    @Tool(name = "cancel_scheduled_task", description = """
            取消已设置的定时任务。
            taskId：要取消的任务ID，设置任务时会返回。
            """)
    public String cancelScheduledTask(
            @ToolParam(description = "要取消的任务ID") String taskId) {

        String userId = CURRENT_USER_ID.get();
        if (userId == null) return "错误：无法获取当前用户信息";
        if (taskId == null || taskId.isBlank()) return "错误：任务ID不能为空";

        long id;
        try { id = Long.parseLong(taskId); } catch (NumberFormatException e) {
            return "错误：无效的任务ID " + taskId;
        }
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }
        taskMemoryRepository.updateStatus(id, "CANCELLED");
        log.info("[定时任务][取消] taskId={}", id);
        return "✅已取消任务：" + id;
    }

    /**
     * 立即执行指定 ID 的定时任务（不等延迟到期）。
     * 取消定时器 -> 立即通过 AI 生成回复并发送 -> 标记为 EXECUTED。
     */
    public String executeNow(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return "请指定要立即执行的任务ID，例如\"立即执行任务6\"。";
        }

        long id;
        try {
            id = Long.parseLong(taskId);
        } catch (NumberFormatException e) {
            return "无效的任务ID：" + taskId;
        }

        TaskMemoryRepository.TaskRow task = taskMemoryRepository.findPendingByType("SCHEDULED")
                .stream()
                .filter(r -> r.id() == id)
                .findFirst()
                .orElse(null);
        if (task == null) {
            return "未找到任务ID：" + taskId;
        }
        if (!"SCHEDULED".equals(task.taskType())) {
            return "该任务不是定时任务（类型：" + task.taskType() + "）";
        }
        if ("CANCELLED".equals(task.status()) || "EXPIRED".equals(task.status())) {
            return "任务 " + taskId + " 已经" + task.status() + "，无法执行";
        }

        // 1.取消定时器:从futures表中移除并取消调度
        ScheduledFuture<?> future = futures.remove(id);
        if (future != null) {
            future.cancel(false);
        }

        // 2.立即通过AI执行任务内容
        String userId = task.userId();
        String taskDesc = task.taskDescription();
        log.info("[定时任务][立即执行][开始] taskId={}, userId={}, task={}", id, userId, taskDesc);
        try {
            String result = aiChatService.chat(userId,
                    "【定时任务触发】" + taskDesc).reply();
            taskMemoryRepository.updateStatus(id, "EXECUTED");
            log.info("[定时任务][立即执行][成功] taskId={}", id);
            sendToUser(userId, "⏰ 定时任务【" + taskDesc + "】已执行：\n" + result);
            return "定时任务【" + taskDesc + "】已立即执行。";
        } catch (Exception e) {
            log.error("[定时任务][立即执行][失败] taskId={}, reason={}", id, e.getMessage());
            return "定时任务执行失败：" + e.getMessage();
        }
    }

    /** 查看定时任务——列出当前用户的所有待执行定时任务。 */
    @Tool(name = "list_scheduled_tasks", description = "查看当前用户的所有待执行定时任务")
    public String listScheduledTasks() {
        String userId = CURRENT_USER_ID.get();
        if (userId == null) return "错误：无法获取当前用户信息";

        List<TaskMemoryRepository.TaskRow> pendingTasks = taskMemoryRepository.findPendingByType("SCHEDULED")
                .stream().filter(r -> userId.equals(r.userId())).toList();

        if (pendingTasks.isEmpty()) {
            return "当前没有待执行的定时任务";
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("📋 待执行的定时任务（共 ");
        sb.append(pendingTasks.size()).append(" 个）：\n\n");

        int idx = 1;
        for (TaskMemoryRepository.TaskRow task : pendingTasks) {
            // 任务名称（优先使用 taskName，若为 null 则用描述或占位）
            String name = task.taskName() != null ? task.taskName() :
                    (task.taskDescription() != null ? task.taskDescription() : "（未命名任务）");

            // 任务描述
            String desc = task.taskDescription() != null ? task.taskDescription() : "（无描述）";

            // 计算执行状态
            String executeStatus;
            Long executeAt = task.executeAt();
            if (executeAt == null) {
                executeStatus = "未设定执行时间";
            } else {
                long remainMs = executeAt - now;
                if (remainMs > 0) {
                    long remainSec = remainMs / 1000;
                    executeStatus = formatDelay(remainSec) + " 后执行";
                } else if (remainMs == 0) {
                    executeStatus = "即将执行（此刻）";
                } else {
                    long overdueSec = -remainMs / 1000;
                    executeStatus = "已超时 " + formatDelay(overdueSec);
                }
            }

            // 采用与周期任务完全相同的格式
            sb.append(String.format(
                    "%d. %s\n"
                            + "任务内容：%s\n"
                            + "执行状态：%s\n"
                            + "任务ID：%d\n\n",
                    idx, name, desc, executeStatus, task.id()
            ));
            idx++;
        }

        sb.append("---\n💡 取消任务请说「取消 + 任务ID」。");
        return sb.toString();
    }

    /** 延迟调度任务：向 ScheduledExecutorService 注册延迟任务。 */
    private void scheduleDelayed(long taskId, String userId, String desc, long delaySeconds) {
        ScheduledFuture<?> future = SCHEDULER.schedule(
                () -> executeAndMark(taskId, userId, desc), delaySeconds, TimeUnit.SECONDS);
        futures.put(taskId, future);
    }

    /** 执行任务并标记状态：执行任务并更新数据库状态。 */
    private void executeAndMark(long taskId, String userId, String taskDescription) {
        futures.remove(taskId);
        try {
            log.info("[定时任务][执行][开始] taskId={}, userId={}, task={}", taskId, userId, taskDescription);
            String result = aiChatService.chat(userId, "【定时任务触发】" + taskDescription).reply();
            taskMemoryRepository.updateStatus(taskId, "EXECUTED");
            sendToUser(userId, result);
            log.info("[定时任务][执行][成功] taskId={}", taskId);
        } catch (Exception e) {
            log.error("[定时任务][执行][失败] taskId={}", taskId, e);
            taskMemoryRepository.updateStatus(taskId, "FAILED");
            sendToUser(userId, "定时任务执行失败：" + e.getMessage());
        }
    }
    /** 通过 iLink 向用户微信发送消息。 */
    private void sendToUser(String userId, String message) {
        try {
            ILinkClient client = clientManager.findClient().orElse(null);
            if (client == null) {
                log.warn("[定时任务][推送] client not available, userId={}", userId);
                return;
            }
            try { client.getUpdates(); } catch (Exception ignored) {}
            client.sendText(userId, message);
            log.info("[定时任务][推送][成功] userId={}", userId);
        } catch (Exception e) {
            log.error("[定时任务][推送][失败] userId={}", userId, e);
        }
    }
    /** 将秒数格式化为人类可读的时间字符串。 */
    private String formatDelay(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }
}
