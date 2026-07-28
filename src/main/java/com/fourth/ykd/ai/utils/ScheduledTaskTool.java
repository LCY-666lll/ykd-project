package com.fourth.ykd.ai.utils;

import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 定时任务工具：支持延迟执行、持久化、取消任务。
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
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> FUTURES = new ConcurrentHashMap<>();

    private final IlinkClientManager clientManager;
    private final AiChatService aiChatService;
    private final JdbcTemplate jdbcTemplate;

    public ScheduledTaskTool(IlinkClientManager clientManager, @Lazy AiChatService aiChatService, JdbcTemplate jdbcTemplate) {
        this.clientManager = clientManager;
        this.aiChatService = aiChatService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public static void setCurrentUserId(String userId) { CURRENT_USER_ID.set(userId); }
    public static void clearCurrentUserId() { CURRENT_USER_ID.remove(); }

    /** 启动时恢复未执行的定时任务 */
    @PostConstruct
    public void restorePendingTasks() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_task (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL UNIQUE,
                    user_id TEXT NOT NULL,
                    action_description TEXT NOT NULL,
                    execute_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    executed_at TEXT
                )
            """);

            long now = System.currentTimeMillis();
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "SELECT task_id, user_id, action_description, execute_at FROM scheduled_task WHERE status = 'PENDING'"
            );

            for (Map<String, Object> task : tasks) {
                String taskId = (String) task.get("task_id");
                String userId = (String) task.get("user_id");
                String desc = (String) task.get("action_description");
                long executeAt = ((Number) task.get("execute_at")).longValue();
                long delaySeconds = (executeAt - now) / 1000;

                if (delaySeconds <= 0) {
                    scheduleImmediate(taskId, userId, desc);
                } else {
                    scheduleDelayed(taskId, userId, desc, delaySeconds);
                }
            }
            if (!tasks.isEmpty()) {
                log.info("[ScheduledTask] restored {} pending tasks", tasks.size());
            }
        } catch (Exception e) {
            log.error("[ScheduledTask] restore failed", e);
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

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long executeAt = System.currentTimeMillis() + delaySeconds * 1000;

        // 持久化到数据库
        jdbcTemplate.update(
                "INSERT INTO scheduled_task (task_id, user_id, action_description, execute_at, status) VALUES (?, ?, ?, ?, 'PENDING')",
                taskId, userId, taskDescription, executeAt
        );

        // 调度执行
        scheduleDelayed(taskId, userId, taskDescription, delaySeconds);

        log.info("[ScheduledTask] scheduled, taskId={}, userId={}, delay={}s, task={}", taskId, userId, delaySeconds, taskDescription);
        return "已设置定时任务，任务ID: " + taskId + "\n将在" + formatDelay(delaySeconds) + "后执行：" + taskDescription + "\n如需取消，请告诉我取消任务" + taskId;
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

        // 从调度器取消
        ScheduledFuture<?> future = FUTURES.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }

        // 从数据库标记为已取消
        int rows = jdbcTemplate.update(
                "UPDATE scheduled_task SET status='CANCELLED', executed_at=CURRENT_TIMESTAMP WHERE task_id=? AND status='PENDING'",
                taskId
        );

        if (rows > 0) {
            log.info("[ScheduledTask] cancelled, taskId={}", taskId);
            return "已取消任务：" + taskId;
        } else {
            return "未找到待执行的任务：" + taskId;
        }
    }

    @Tool(name = "list_scheduled_tasks", description = "查看当前用户的所有待执行定时任务")
    public String listScheduledTasks() {
        String userId = CURRENT_USER_ID.get();
        if (userId == null) return "错误：无法获取当前用户信息";

        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "SELECT task_id, action_description, execute_at FROM scheduled_task WHERE user_id=? AND status='PENDING' ORDER BY execute_at",
                userId
        );

        if (tasks.isEmpty()) {
            return "当前没有待执行的定时任务";
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("待执行的定时任务：\n");
        for (Map<String, Object> task : tasks) {
            String taskId = (String) task.get("task_id");
            String desc = (String) task.get("action_description");
            long executeAt = ((Number) task.get("execute_at")).longValue();
            long remainSeconds = (executeAt - now) / 1000;
            sb.append("• ").append(desc);
            if (remainSeconds > 0) {
                sb.append("（").append(formatDelay(remainSeconds)).append("后执行）");
            } else {
                sb.append("（即将执行）");
            }
            sb.append("\n任务ID: ").append(taskId).append("\n");
        }
        return sb.toString().trim();
    }

    /** 延迟调度任务 */
    private void scheduleDelayed(String taskId, String userId, String desc, long delaySeconds) {
        ScheduledFuture<?> future = SCHEDULER.schedule(
                () -> executeAndMark(taskId, userId, desc), delaySeconds, TimeUnit.SECONDS);
        FUTURES.put(taskId, future);
    }

    /** 立即调度任务 */
    private void scheduleImmediate(String taskId, String userId, String desc) {
        SCHEDULER.schedule(() -> executeAndMark(taskId, userId, desc), 0, TimeUnit.SECONDS);
        log.info("[ScheduledTask] immediate execution, taskId={}", taskId);
    }

    /** 执行任务并标记状态 */
    private void executeAndMark(String taskId, String userId, String taskDescription) {
        FUTURES.remove(taskId);
        try {
            log.info("[ScheduledTask] executing, taskId={}, userId={}, task={}", taskId, userId, taskDescription);
            String result = aiChatService.chat(userId, "【定时任务触发】" + taskDescription).reply();
            sendToUser(userId, result);
            jdbcTemplate.update("UPDATE scheduled_task SET status='EXECUTED', executed_at=CURRENT_TIMESTAMP WHERE task_id=?", taskId);
            log.info("[ScheduledTask] executed successfully, taskId={}", taskId);
        } catch (Exception e) {
            log.error("[ScheduledTask] execution failed, taskId={}", taskId, e);
            sendToUser(userId, "定时任务执行失败：" + e.getMessage());
            jdbcTemplate.update("UPDATE scheduled_task SET status='FAILED', executed_at=CURRENT_TIMESTAMP WHERE task_id=?", taskId);
        }
    }

    private void sendToUser(String userId, String message) {
        try {
            ILinkClient client = clientManager.findClient().orElse(null);
            if (client == null) {
                log.warn("[ScheduledTask] client not available, userId={}", userId);
                return;
            }
            try { client.getUpdates(); } catch (Exception ignored) {}
            client.sendText(userId, message);
            log.info("[ScheduledTask] sent to userId={}", userId);
        } catch (Exception e) {
            log.error("[ScheduledTask] send failed, userId={}", userId, e);
        }
    }

    private String formatDelay(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时" + ((seconds % 3600) / 60) + "分钟";
    }
}