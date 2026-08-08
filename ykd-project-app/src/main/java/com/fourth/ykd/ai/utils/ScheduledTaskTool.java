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
 * 定时任务工具：支持延迟执行、周期循环执行、持久化、取消任务。
 */
@Slf4j
@Component
public class ScheduledTaskTool {
//用于保存“当前正在和 AI 对话的用户 ID”。因为 AI 调用工具方法时，工具方法本身没有用户参数，所以 AiChatServiceImpl 在调用前设置，调用后清理
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    //用于标记当前是否正在执行系统自动任务。防止自动执行“每隔30秒发送你好”时，模型又去创建新的定时任务。
    private static final ThreadLocal<Boolean> AUTO_TASK_EXECUTION = ThreadLocal.withInitial(() -> Boolean.FALSE);
    //项目内的轻量调度线程池，用来执行延迟任务和周期任务。
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "scheduled-task");
        t.setDaemon(true);
        return t;
    });
    /** 存储任务ID对应的Future，用于取消任务 */
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> FUTURES = new ConcurrentHashMap<>();
//保存 taskId -> ScheduledFuture，这样取消任务时可以找到对应的调度任务并取消
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
    public static String getCurrentUserId() { return CURRENT_USER_ID.get(); }

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
                    schedule_type TEXT NOT NULL DEFAULT 'ONCE',
                    interval_seconds INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    executed_at TEXT
                )
            """);
            //2. 对旧表执行兼容性迁移：
            ensureColumn("schedule_type", "TEXT NOT NULL DEFAULT 'ONCE'");
            ensureColumn("interval_seconds", "INTEGER NOT NULL DEFAULT 0");
//查询所有 PENDING 任务。
//  4. 对周期任务，先跳过已经错过的周期
            long now = System.currentTimeMillis();
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "SELECT task_id, user_id, action_description, execute_at, schedule_type, interval_seconds "
                            + "FROM scheduled_task WHERE status = 'PENDING'"
            );

            for (Map<String, Object> task : tasks) {
                String taskId = (String) task.get("task_id");
                String userId = (String) task.get("user_id");
                String desc = (String) task.get("action_description");
                long executeAt = ((Number) task.get("execute_at")).longValue();
                long intervalSeconds = task.get("interval_seconds") == null
                        ? 0
                        : ((Number) task.get("interval_seconds")).longValue();
            // 重新调用 scheduleRepeatingAt() 调度下一次执行。
                if (isRepeatType(task.get("schedule_type")) && intervalSeconds > 0) {
                    long nextExecuteAt = skipMissedRepeatingRuns(executeAt, intervalSeconds, now);
                    jdbcTemplate.update(
                            "UPDATE scheduled_task SET execute_at=? WHERE task_id=? AND status='PENDING'",
                            nextExecuteAt, taskId
                    );
                    scheduleRepeatingAt(taskId, userId, desc, intervalSeconds, nextExecuteAt);
                } else {
                    long delaySeconds = (executeAt - now) / 1000;
                    if (delaySeconds <= 0) {
                        scheduleImmediate(taskId, userId, desc);
                    } else {
                        scheduleDelayed(taskId, userId, desc, delaySeconds);
                    }
                }
            }
            if (!tasks.isEmpty()) {
                log.info("[ScheduledTask] restored {} pending tasks", tasks.size());
            }
        } catch (Exception e) {
            log.error("[ScheduledTask] restore failed", e);
        }
    }

    private void ensureColumn(String columnName, String definition) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(scheduled_task)");
        boolean exists = columns.stream()
                .map(row -> {
                    Object name = row.get("name");
                    if (name == null) {
                        name = row.entrySet().stream()
                                .filter(entry -> entry.getKey().equalsIgnoreCase("name"))
                                .map(Map.Entry::getValue)
                                .findFirst()
                                .orElse(null);
                    }
                    return String.valueOf(name);
                })
                .anyMatch(name -> columnName.equalsIgnoreCase(name));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE scheduled_task ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean isRepeatType(Object scheduleType) {
        return "REPEAT".equals(scheduleType == null ? "ONCE" : String.valueOf(scheduleType));
    }

    private long skipMissedRepeatingRuns(long executeAt, long intervalSeconds, long now) {
        long intervalMillis = intervalSeconds * 1000L;
        long nextExecuteAt = executeAt;
        while (nextExecuteAt <= now) {
            nextExecuteAt += intervalMillis;
        }
        return nextExecuteAt;
    }

    @Tool(name = "schedule_task", description = """
            设置定时任务，延迟指定秒数后自动执行。
            可执行任意任务：查询天气、提醒事项、搜索新闻、生成图片、翻译等。
            这是单次任务，执行一次后结束；需要每隔一段时间循环执行时使用 schedule_repeating_task。
            返回taskId，可用于取消任务。
            taskDescription：任务描述，AI会根据描述自动选择合适的工具执行。
            delaySeconds：延迟秒数，如60=1分钟，300=5分钟，1800=30分钟。
            """)
    public String scheduleTask(
            @ToolParam(description = "任务描述，如'查询北京天气'、'提醒我喝水'、'生成一张风景图'") String taskDescription,
            @ToolParam(description = "延迟秒数，如60表示1分钟后") long delaySeconds) {
//获取当前用户
        String userId = CURRENT_USER_ID.get();
        if (userId == null) return "错误：无法获取当前用户信息";
        if (Boolean.TRUE.equals(AUTO_TASK_EXECUTION.get())) return "错误：系统自动任务执行中，不能再次创建定时任务";
        //校验 delaySeconds
        if (delaySeconds <= 0 || delaySeconds > 86400) return "错误：延迟时间需在1秒到24小时之间";
//UUID 生成 taskId
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

    @Tool(name = "schedule_repeating_task", description = """
            设置周期循环定时任务，按固定时间间隔反复执行，并自动通过工具查询本轮最新结果。
            可执行任意任务：每隔30秒查询并发送天气、每隔1小时查询新闻、周期性提醒等。
            返回taskId，可用于取消任务。
            taskDescription：任务描述，AI会根据描述自动选择合适的工具执行。
            intervalSeconds：执行间隔秒数，如30=每30秒，300=每5分钟，3600=每小时。
            """)
    public String scheduleRepeatingTask(
            @ToolParam(description = "任务描述，如'每隔30秒查询北京天气并发送给我'") String taskDescription,
            @ToolParam(description = "执行间隔秒数，至少1秒；如30表示每30秒执行一次") long intervalSeconds) {

        String userId = CURRENT_USER_ID.get();
        if (userId == null) {
            return "错误：无法获取当前用户信息";
        }
        if (Boolean.TRUE.equals(AUTO_TASK_EXECUTION.get())) {
            return "错误：系统自动任务执行中，不能再次创建定时任务";
        }
        if (intervalSeconds < 1 || intervalSeconds > 86400) {
            return "错误：执行间隔需在1秒到24小时之间";
        }

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long executeAt = System.currentTimeMillis() + intervalSeconds * 1000;

        jdbcTemplate.update(
                "INSERT INTO scheduled_task "
                        + "(task_id, user_id, action_description, execute_at, schedule_type, interval_seconds, status) "
                        + "VALUES (?, ?, ?, ?, 'REPEAT', ?, 'PENDING')",
                taskId, userId, taskDescription, executeAt, intervalSeconds
        );

        scheduleRepeatingAt(taskId, userId, taskDescription, intervalSeconds, executeAt);

        log.info("[ScheduledTask] repeating scheduled, taskId={}, userId={}, interval={}s, task={}",
                taskId, userId, intervalSeconds, taskDescription);
        return "已设置周期循环任务，任务ID: " + taskId
                + "\n每" + formatInterval(intervalSeconds) + "执行一次：" + taskDescription
                + "\n如需取消，请告诉我取消任务" + taskId;
    }

    @Tool(name = "cancel_scheduled_task", description = """
            取消已设置的定时、周期任务。
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
                "SELECT task_id, action_description, execute_at, schedule_type, interval_seconds "
                        + "FROM scheduled_task WHERE user_id=? AND status='PENDING' ORDER BY execute_at",
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
            if (isRepeatType(task.get("schedule_type"))) {
                long intervalSeconds = task.get("interval_seconds") == null
                        ? 0
                        : ((Number) task.get("interval_seconds")).longValue();
                sb.append("（每").append(formatInterval(intervalSeconds)).append("执行一次");
                if (remainSeconds > 0) {
                    sb.append("，").append(formatDelay(remainSeconds)).append("后执行）");
                } else {
                    sb.append("，即将执行）");
                }
            } else if (remainSeconds > 0) {
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

    /** 按数据库中的下次执行时间调度周期任务 */
    private void scheduleRepeatingAt(String taskId, String userId, String desc, long intervalSeconds,
            long executeAt) {
        long delayMillis = Math.max(0, executeAt - System.currentTimeMillis());
        ScheduledFuture<?> future = SCHEDULER.schedule(
                () -> executeRepeatingAndReschedule(taskId, userId, desc, intervalSeconds, executeAt),
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        FUTURES.put(taskId, future);
    }

    /** 执行任务并标记状态 */
    private void executeAndMark(String taskId, String userId, String taskDescription) {
        FUTURES.remove(taskId);
        try {
            //判断是否仍是 PENDING
            if (!isTaskPending(taskId)) {
                return;
            }
            log.info("[ScheduledTask] executing, taskId={}, userId={}, task={}", taskId, userId, taskDescription);
            String result = executeScheduledChatAI(//调用 AI 执行任务
                    userId,
                    buildScheduledTaskPrompt("【定时任务触发】", taskDescription)
            );//通过 iLink 发送
            sendToUser(userId, result);
            //标记 EXECUTED/FAILED
            jdbcTemplate.update("UPDATE scheduled_task SET status='EXECUTED', executed_at=CURRENT_TIMESTAMP WHERE task_id=?", taskId);
            log.info("[ScheduledTask] executed successfully, taskId={}", taskId);
        } catch (Exception e) {
            log.error("[ScheduledTask] execution failed, taskId={}", taskId, e);
            sendToUser(userId, "定时任务执行失败：" + e.getMessage());
            jdbcTemplate.update("UPDATE scheduled_task SET status='FAILED', executed_at=CURRENT_TIMESTAMP WHERE task_id=?", taskId);
        }
    }

    /** 执行周期任务一次，并安排下一次未来触发；错过或失败不会终止周期任务 */
    private void executeRepeatingAndReschedule(String taskId, String userId, String taskDescription,
            long intervalSeconds, long plannedExecuteAt) {
        FUTURES.remove(taskId);
        try {
            if (!isTaskPending(taskId)) {
                return;
            }
            log.info("[ScheduledTask] repeating execution, taskId={}, userId={}, plannedExecuteAt={}, task={}",
                    taskId, userId, plannedExecuteAt, taskDescription);
            String result = executeScheduledChatAI(
                    userId,
                    buildScheduledTaskPrompt("【周期任务触发】", taskDescription)
            );
            if (isTaskPending(taskId)) {
                sendToUser(userId, result);
                jdbcTemplate.update(
                        "UPDATE scheduled_task SET executed_at=CURRENT_TIMESTAMP WHERE task_id=?",
                        taskId
                );
            }
            log.info("[ScheduledTask] repeating execution finished, taskId={}", taskId);
        } catch (Exception e) {
            log.error("[ScheduledTask] repeating execution failed, taskId={}", taskId, e);
            sendToUser(userId, "周期任务执行失败：" + e.getMessage());
        } finally {
            try {
                if (isTaskPending(taskId)) {
                    //计算 nextExecuteAt
                    long nextExecuteAt = plannedExecuteAt + intervalSeconds * 1000L;
                    //更新 execute_at
                    jdbcTemplate.update(
                            "UPDATE scheduled_task SET execute_at=? WHERE task_id=? AND status='PENDING'",
                            nextExecuteAt, taskId
                    );
                    scheduleRepeatingAt(taskId, userId, taskDescription, intervalSeconds, nextExecuteAt);
                }
            } catch (Exception e) {
                log.error("[ScheduledTask] repeating reschedule failed, taskId={}", taskId, e);
            }
        }
    }

    private boolean isTaskPending(String taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task WHERE task_id=? AND status='PENDING'",
                Integer.class,
                taskId
        );
        return count != null && count > 0;
    }

    private String executeScheduledChatAI(String userId, String prompt) {
        AUTO_TASK_EXECUTION.set(true);
        try {
            return aiChatService.chat(userId, prompt).reply();
        } finally {
            AUTO_TASK_EXECUTION.remove();
        }
    }

    private String buildScheduledTaskPrompt(String triggerLabel, String taskDescription) {
        return triggerLabel
                + "这是系统按用户设定自动执行的任务，请直接执行任务内容；"
                + "禁止再次调用 schedule_task 或 schedule_repeating_task，禁止要求用户确认，"
                + "必须根据本轮工具结果回答。任务内容："
                + taskDescription;
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

    private String formatInterval(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        return (seconds / 3600) + "小时";
    }
}
