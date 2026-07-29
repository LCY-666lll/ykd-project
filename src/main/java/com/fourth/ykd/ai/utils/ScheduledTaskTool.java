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
 * 定时任务工具：支持延迟执行、SQLite 持久化、取消任务。
 */
@Slf4j
@Component
public class ScheduledTaskTool {

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "scheduled-task");
        t.setDaemon(true);
        return t;
    });

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

    public static void setCurrentUserId(String userId) { CURRENT_USER_ID.set(userId); }
    public static void clearCurrentUserId() { CURRENT_USER_ID.remove(); }

    // ==================== 启动恢复 ====================

    /** 从 task_memory 表加载未执行的定时任务。 */
    @PostConstruct
    private void loadTasks() {
        List<TaskMemoryRepository.TaskRow> pending = taskMemoryRepository.findPendingByType("SCHEDULED");
        long now = System.currentTimeMillis();
        int restored = 0;
        for (TaskMemoryRepository.TaskRow row : pending) {
            if (row.executeAt() != null && row.executeAt() <= now) {
                taskMemoryRepository.updateStatus(row.id(), "EXPIRED");
                continue;
            }
            long delaySeconds = row.executeAt() != null
                    ? Math.max(1, (row.executeAt() - now) / 1000)
                    : 60;
            scheduleDelayed(row.id(), row.userId(), row.taskDescription(), delaySeconds);
            restored++;
        }
        if (restored > 0) {
            log.info("[定时任务][恢复] 从 task_memory 恢复了 {} 个待执行任务", restored);
        }
    }

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

        // 持久化到数据库
        long taskId = taskMemoryRepository.insert(
                "SCHEDULED", "定时提醒", userId, null, taskDescription,
                executeAt);

        // 调度执行
        scheduleDelayed(taskId, userId, taskDescription, delaySeconds);

        log.info("[定时任务][创建] taskId={}, userId={}, delay={}s, task={}", taskId, userId, delaySeconds, taskDescription);
        return "已设置定时任务，任务ID: " + taskId + "\n将在" + formatDelay(delaySeconds) + "后执行：" + taskDescription + "\n如需取消，请告诉我取消任务" + taskId;
    }

    /** 由 IlinkReplyProcessor 调用：AI 解析用户文本后直接调度，绕过 DeepSeek 工具调用。 */
    public String parseAndSchedule(String userId, String userText) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String aiResult = aiChatService.chat(userId,
                "【系统指令】根据用户文本输出严格JSON，不要解释，不要Markdown：\n"
                + "{\"delaySeconds\":<数字>,\"taskDescription\":\"<描述>\"}\n"
                + "delaySeconds为从现在到目标时间的秒数（最大86400=24小时）。\n"
                + "当前时间：" + now + "\n"
                + "用户文本：" + userText).reply();

        long delaySeconds;
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

        CURRENT_USER_ID.set(userId);
        try {
            return scheduleTask(taskDescription, delaySeconds);
        } finally {
            CURRENT_USER_ID.remove();
        }
    }

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
        return "已取消任务：" + id;
    }

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
        StringBuilder sb = new StringBuilder("待执行的定时任务：\n");
        for (TaskMemoryRepository.TaskRow task : pendingTasks) {
            long id = task.id();
            String desc = task.taskDescription();
            long executeAt = task.executeAt() != null ? task.executeAt() : 0L;
            long remainSeconds = (executeAt - now) / 1000;
            sb.append("• ").append(desc);
            if (remainSeconds > 0) {
                sb.append("（").append(formatDelay(remainSeconds)).append("后执行）");
            } else {
                sb.append("（即将执行）");
            }
            sb.append("\n任务ID: ").append(id).append("\n");
        }
        return sb.toString().trim();
    }

    /** 延迟调度任务 */
    private void scheduleDelayed(long taskId, String userId, String desc, long delaySeconds) {
        ScheduledFuture<?> future = SCHEDULER.schedule(
                () -> executeAndMark(taskId, userId, desc), delaySeconds, TimeUnit.SECONDS);
        futures.put(taskId, future);
    }

    /** 执行任务并标记状态 */
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

    private String formatDelay(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }
}
