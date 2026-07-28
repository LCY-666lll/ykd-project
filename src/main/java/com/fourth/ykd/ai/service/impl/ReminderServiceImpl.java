package com.fourth.ykd.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourth.ykd.ai.dto.ReminderTask;
import com.fourth.ykd.ai.infrastructure.memory.ReminderTaskRepository;
import com.fourth.ykd.ai.service.ReminderService;
import com.fourth.ykd.ai.utils.DynamicSchedulerTool;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** 提醒服务：自然语言创建 → SQLite 持久化 → 调度 → 启动恢复 → iLink 推送。 */
@Slf4j
@Service
public class ReminderServiceImpl implements ReminderService {

    private static final String PARSE_PROMPT = """
            你是一个提醒任务解析器。根据用户的自然语言输入和当前时间参考，提取提醒时间语义和提醒内容。
            当前时间参考：%s（仅用于理解"今天/明天/后天"等相对日期语义，不要用于计算绝对时间戳）

            解析规则：
            - 相对时长提醒（如"10秒后"、"30分钟后"、"2小时后"、"1天后"）：
              输出 {"type":"ONCE","delaySeconds":<相对总秒数>,"content":"<提醒内容>"}
              注意：delaySeconds 是相对当前时间的精确秒数偏移，由 Java 代码计算最终触发时间
            - 钟点时间提醒（如"晚上8点"、"下午3点开会"）：
              输出 {"type":"ONCE","targetTime":"<HH:mm>","content":"<提醒内容>"}
              注意：targetTime 使用24小时制，如晚上8点="20:00"，只输出时间部分不输出日期
            - 重复提醒（如"每天早上8点提醒我喝水"）：
              输出 {"type":"CRON","cronExpression":"<七段cron表达式>","content":"<提醒内容>"}
              cron 表达式格式：秒 分 时 日 月 周，例如每天8点 = "0 0 8 * * ?"
              注意：cron 中使用24小时制小时，0=凌晨0点，8=早上8点，13=下午1点
            - content 必须简洁描述提醒事项，不要加"提醒"二字
            - 只输出 JSON，不要解释，不要 Markdown
            - 严禁输出 triggerTimeMs 字段，时间计算由 Java 代码完成
            """;

    private final ReminderTaskRepository repository;
    private final DynamicSchedulerTool schedulerTool;
    private final IlinkClientManager clientManager;
    private final ChatClient parseChatClient;
    private final ObjectMapper objectMapper;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public ReminderServiceImpl(ReminderTaskRepository repository,
            DynamicSchedulerTool schedulerTool,
            IlinkClientManager clientManager,
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.schedulerTool = schedulerTool;
        this.clientManager = clientManager;
        this.parseChatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** 项目启动时从 SQLite 恢复所有提醒任务并重新调度。 */
    @PostConstruct
    public void recoverTasks() {
        List<ReminderTask> tasks = repository.findAll();
        if (tasks.isEmpty()) {
            log.info("[REMINDER][RECOVER] 无待恢复的提醒任务");
            return;
        }
        int recovered = 0;
        int skipped = 0;
        for (ReminderTask task : tasks) {
            try {
                if (task.getTriggerTimeMs() != null) {
                    Instant triggerTime = Instant.ofEpochMilli(task.getTriggerTimeMs());
                    if (triggerTime.isBefore(Instant.now())) {
                        log.info("[REMINDER][RECOVER][SKIP_EXPIRED] id={}, userId={}, triggerTime={}",
                                task.getId(), task.getUserId(), triggerTime);
                        repository.deleteById(task.getId());
                        skipped++;
                        continue;
                    }
                    scheduleTask(task);
                    recovered++;
                } else if (task.getCronExpression() != null && !task.getCronExpression().isBlank()) {
                    scheduleTask(task);
                    recovered++;
                }
            } catch (Exception e) {
                log.error("[REMINDER][RECOVER][FAILED] id={}, userId={}", task.getId(), task.getUserId(), e);
            }
        }
        log.info("[REMINDER][RECOVER] 恢复完成, recovered={}, skipped={}", recovered, skipped);
    }

    @Override
    public String createReminder(String userId, String contextToken, String userText) {
        try {
            // 1. 用 AI 解析自然语言
            String nowStr = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String prompt = String.format(PARSE_PROMPT, nowStr);
            String aiResult = parseChatClient.prompt()
                    .system(prompt)
                    .user(userText)
                    .call()
                    .content();
            log.info("[REMINDER][CREATE][PARSE] userId={}, userText={}, aiResult={}", userId, userText, aiResult);

            // 2. 解析 JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(extractJson(aiResult), Map.class);
            String type = (String) parsed.get("type");
            String content = (String) parsed.get("content");

            if (content == null || content.isBlank()) {
                return "未能识别提醒内容，请说清楚要提醒什么，例如「提醒我明天下午3点开会」。";
            }

            ReminderTask task = new ReminderTask();
            task.setId(UUID.randomUUID().toString());
            task.setUserId(userId);
            task.setContent(content.trim());
            task.setContextToken(contextToken);

            if ("CRON".equalsIgnoreCase(type)) {
                task.setCronExpression((String) parsed.get("cronExpression"));
            } else {
                task.setTriggerTimeMs(computeTriggerTimeMs(parsed));
            }

            // 3. 时间校验：提醒时间必须晚于当前时间
            if (task.getTriggerTimeMs() != null && task.getTriggerTimeMs() <= System.currentTimeMillis()) {
                log.warn("[REMINDER][CREATE][REJECTED_EXPIRED] triggerTimeMs={}, now={}",
                        task.getTriggerTimeMs(), System.currentTimeMillis());
                return "提醒时间必须晚于当前时间，请重新设置。";
            }

            // 4. 持久化到 SQLite
            repository.save(task);

            // 5. 调度任务
            scheduleTask(task);

            String timeDesc = task.getCronExpression() != null
                    ? "按" + task.getCronExpression()
                    : Instant.ofEpochMilli(task.getTriggerTimeMs()).atZone(ZoneId.of("Asia/Shanghai"))
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            log.info("[REMINDER][CREATE][SUCCESS] id={}, userId={}, content={}, time={}",
                    task.getId(), userId, task.getContent(), timeDesc);
            return "好的，已设置提醒：" + task.getContent() + "（" + timeDesc + "触发）";

        } catch (Exception e) {
            log.error("[REMINDER][CREATE][FAILED] userId={}, userText={}", userId, userText, e);
            return "提醒设置失败，请稍后重试。";
        }
    }

    @Override
    public String deleteReminder(String userId) {
        try {
            List<ReminderTask> userTasks = repository.findByUserId(userId);
            if (userTasks.isEmpty()) {
                return "你当前没有待执行的提醒。";
            }
            int count = userTasks.size();
            for (ReminderTask task : userTasks) {
                ScheduledFuture<?> future = activeTasks.remove(task.getId());
                if (future != null) {
                    schedulerTool.cancel(future);
                }
            }
            repository.deleteByUserId(userId);
            log.info("[REMINDER][DELETE][SUCCESS] userId={}, count={}", userId, count);
            return "已取消" + count + "个提醒。";
        } catch (Exception e) {
            log.error("[REMINDER][DELETE][FAILED] userId={}", userId, e);
            return "取消提醒失败，请稍后重试。";
        }
    }

    /**
     * 根据 AI 解析的语义结果计算绝对触发时间戳（毫秒）。
     * <p>支持两种语义格式：</p>
     * <ul>
     *   <li>delaySeconds：相对当前时间的秒数偏移</li>
     *   <li>targetTime：钟点时间（HH:mm），由 Java 结合当前日期计算</li>
     * </ul>
     */
    private long computeTriggerTimeMs(Map<String, Object> parsed) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.now(zone);

        // 1. 相对时长：delaySeconds
        Object delayObj = parsed.get("delaySeconds");
        if (delayObj instanceof Number && ((Number) delayObj).longValue() > 0) {
            long delaySeconds = ((Number) delayObj).longValue();
            long triggerTimeMs = System.currentTimeMillis() + delaySeconds * 1000;
            log.info("[REMINDER][COMPUTE] delaySeconds={}, triggerTime={}", delaySeconds,
                    Instant.ofEpochMilli(triggerTimeMs).atZone(zone));
            return triggerTimeMs;
        }

        // 2. 钟点时间：targetTime (HH:mm)
        Object timeObj = parsed.get("targetTime");
        if (timeObj instanceof String && !((String) timeObj).isBlank()) {
            String targetTime = (String) timeObj;
            String[] parts = targetTime.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            ZonedDateTime target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            // 如果目标时间已过，推到明天同一时间
            if (!target.isAfter(now)) {
                target = target.plusDays(1);
            }
            log.info("[REMINDER][COMPUTE] targetTime={}, resolved={}", targetTime, target);
            return target.toInstant().toEpochMilli();
        }

        throw new RuntimeException("AI 解析结果中未包含有效的时间字段（delaySeconds 或 targetTime）");
    }

    /** 调度一个提醒任务，到时间通过 iLink 推送。 */
    private void scheduleTask(ReminderTask task) {
        Runnable runnable = () -> executeReminder(task);
        ScheduledFuture<?> future;
        if (task.getCronExpression() != null && !task.getCronExpression().isBlank()) {
            future = schedulerTool.scheduleCron(runnable, task.getCronExpression());
            log.info("[REMINDER][SCHEDULED_CRON] id={}, cron={}", task.getId(), task.getCronExpression());
        } else {
            Instant triggerTime = Instant.ofEpochMilli(task.getTriggerTimeMs());
            future = schedulerTool.scheduleAt(runnable, triggerTime);
            log.info("[REMINDER][SCHEDULED_AT] id={}, triggerTime={}", task.getId(), triggerTime);
        }
        activeTasks.put(task.getId(), future);
    }

    /** 提醒到期时执行推送并清理一次性任务。 */
    private void executeReminder(ReminderTask task) {
        log.info("[REMINDER][FIRE] id={}, userId={}, content={}, hasContextToken={}",
                task.getId(), task.getUserId(), task.getContent(), task.getContextToken() != null);
        clientManager.findClient().ifPresent(client -> {
            try {
                // 使用持久化 contextToken 发送（当前 SDK sendText 不直接接受 contextToken 参数，
                // 若后续 SDK 升级支持 sendText(userId, text, contextToken) 可替换此处调用）
                client.sendText(task.getUserId(), task.getContent());
                log.info("[REMINDER][PUSHED] id={}, userId={}", task.getId(), task.getUserId());
            } catch (IOException e) {
                log.error("[REMINDER][PUSH_FAILED] id={}, userId={}", task.getId(), task.getUserId(), e);
            }
        });
        if (clientManager.findClient().isEmpty()) {
            log.warn("[REMINDER][PUSH_SKIPPED] id={}, userId={}, reason=NO_ILINK_CLIENT",
                    task.getId(), task.getUserId());
        }
        // 一次性任务触发后清理
        if (task.getCronExpression() == null || task.getCronExpression().isBlank()) {
            activeTasks.remove(task.getId());
            repository.deleteById(task.getId());
            log.info("[REMINDER][CLEANED] id={}", task.getId());
        }
    }

    /** 从 AI 返回中提取 JSON 字符串。 */
    private static String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        text = text.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}