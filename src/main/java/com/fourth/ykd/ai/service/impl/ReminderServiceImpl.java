package com.fourth.ykd.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourth.ykd.ai.dto.ReminderTask;
import com.fourth.ykd.ai.infrastructure.memory.ReminderTaskRepository;
import com.fourth.ykd.ai.service.ReminderService;
import com.fourth.ykd.ai.utils.BaiduSearchTool;
import com.fourth.ykd.ai.utils.DynamicSchedulerTool;
import com.fourth.ykd.ai.utils.MathCalculatorTool;
import com.fourth.ykd.ai.utils.TimeTool;
import com.fourth.ykd.ai.utils.TranslationTool;
import com.fourth.ykd.ai.utils.WeatherTool;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import jakarta.annotation.PostConstruct;
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

/** 提醒服务：自然语言创建 → SQLite 持久化 → 调度 → 启动恢复 → AI Agent 执行 → iLink 推送。 */
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

    private static final String AGENT_PROMPT = """
            你是微信助手。用户此前设置了一条定时提醒，现在到了提醒时间。
            请根据用户的提醒内容，决定是否需要调用工具生成有实际价值的回复内容。

            行为规则：
            1. 如果提醒内容是查询类（如"查询天气"、"搜索新闻"、"翻译XX"、"计算XX"）：
               必须调用相应工具获取最新数据，基于工具返回结果生成回复。
            2. 如果提醒内容是简单的提醒类（如"喝水"、"开会"、"休息"）：
               直接生成一句温馨的提醒文本即可，不要调用工具。
            3. 每次提醒执行视为独立任务，不要参考之前的聊天记忆。
            4. 使用中文回复，简洁明了。
            """;

    private final ReminderTaskRepository repository;
    private final DynamicSchedulerTool schedulerTool;
    private final IlinkClientManager clientManager;
    private final ChatClient parseChatClient;
    private final ChatClient agentChatClient;
    private final ObjectMapper objectMapper;

    private final WeatherTool weatherTool;
    private final BaiduSearchTool baiduSearchTool;
    private final TimeTool timeTool;
    private final MathCalculatorTool mathCalculatorTool;
    private final TranslationTool translationTool;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    /** 推送失败重试计数器：taskId -> 已重试次数。 */
    private final Map<String, Integer> retryCountMap = new ConcurrentHashMap<>();
    /** 推送失败最大重试次数。 */
    private static final int MAX_RETRIES = 10;
    /** 重试间隔（毫秒），每次重试递增直到 60 秒上限。 */
    private static final long BASE_RETRY_DELAY_MS = 15_000L;
    private static final long MAX_RETRY_DELAY_MS = 60_000L;

    public ReminderServiceImpl(ReminderTaskRepository repository,
            DynamicSchedulerTool schedulerTool,
            IlinkClientManager clientManager,
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            WeatherTool weatherTool,
            BaiduSearchTool baiduSearchTool,
            TimeTool timeTool,
            MathCalculatorTool mathCalculatorTool,
            TranslationTool translationTool) {
        this.repository = repository;
        this.schedulerTool = schedulerTool;
        this.clientManager = clientManager;
        this.parseChatClient = chatClientBuilder.build();
        this.agentChatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.weatherTool = weatherTool;
        this.baiduSearchTool = baiduSearchTool;
        this.timeTool = timeTool;
        this.mathCalculatorTool = mathCalculatorTool;
        this.translationTool = translationTool;
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

            // BugFix#1: AI 判定为非提醒类意图（UNKNOWN）时短路，不进入时间计算和任务创建流程
            // 直接返回 AI 解析的 content 作为对话文本，交给上游处理
            if ("UNKNOWN".equalsIgnoreCase(type)) {
                log.info("[REMINDER][CREATE][UNKNOWN] userId={}, userText={}, content={}",
                        userId, userText, content);
                return content;
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
            long diffMs = target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli();
            // BugFix#2: 增加容差窗口，防止同一分钟内发消息被推到明天
            // - 已过点但在 2 分钟内：立即触发（now + 1 秒）
            // - 将在 5 分钟内到达：正常调度到今天
            // - 已过点超过 2 分钟：推到明天
            if (diffMs < 0) {
                if (Math.abs(diffMs) <= 2 * 60 * 1000) {
                    // 刚过点 2 分钟内，立即触发
                    long immediateMs = System.currentTimeMillis() + 1000;
                    log.info("[REMINDER][COMPUTE] targetTime={}, diffMs={}, within tolerance, trigger immediately at {}",
                            targetTime, diffMs, Instant.ofEpochMilli(immediateMs).atZone(zone));
                    return immediateMs;
                }
                // 已过点超过容忍窗口，推到明天
                target = target.plusDays(1);
            }
            // diffMs >= 0：未来时间，正常调度（包括 5 分钟内的近时时间）
            log.info("[REMINDER][COMPUTE] targetTime={}, diffMs={}, resolved={}", targetTime, diffMs, target);
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

    /** 提醒到期时通过 AI Agent 执行，支持 Tool Calling，最终通过 iLink 推送。 */
    private void executeReminder(ReminderTask task) {
        String userId = task.getUserId();
        String content = task.getContent();
        String contextToken = task.getContextToken();
        log.info("[REMINDER][FIRE] id={}, userId={}, content={}, hasContextToken={}",
                task.getId(), userId, content, contextToken != null);

        boolean pushed = false;

        try {
            // 1. AI Agent 执行：根据提醒内容决定是否调用工具
            String aiAnswer = agentChatClient.prompt()
                    .system(AGENT_PROMPT)
                    .user("提醒内容：" + content)
                    .tools(weatherTool, baiduSearchTool, timeTool, mathCalculatorTool, translationTool)
                    .call()
                    .content();
            log.info("[REMINDER][AGENT][SUCCESS] id={}, userId={}, answerLength={}",
                    task.getId(), userId, aiAnswer != null ? aiAnswer.length() : 0);

            // 2. 通过 iLink 发送 AI 生成的回复，传递 contextToken 恢复会话上下文
            clientManager.sendText(userId, aiAnswer != null ? aiAnswer : content, contextToken);
            pushed = true;
            log.info("[REMINDER][PUSHED] id={}, userId={}", task.getId(), userId);

        } catch (Exception e) {
            log.error("[REMINDER][AGENT_FAILED] id={}, userId={}, falling back to direct text",
                    task.getId(), userId, e);
            // 3. 降级兜底：AI 调用失败时直接发送原始提醒内容
            try {
                clientManager.sendText(userId, content, contextToken);
                pushed = true;
                log.info("[REMINDER][FALLBACK_PUSHED] id={}, userId={}", task.getId(), userId);
            } catch (Exception fallbackEx) {
                log.error("[REMINDER][FALLBACK_FAILED] id={}, userId={}", task.getId(), userId, fallbackEx);
                // 推送失败由 handlePushFailure 统一处理重试/清理
            }
        }

        if (pushed) {
            retryCountMap.remove(task.getId());
            cleanupOneShot(task);
        } else {
            handlePushFailure(task);
        }
    }

    /**
     * 处理推送失败：contextToken 过期时重试，其他错误或重试耗尽时清理。
     * <p>
     * 重新扫码登录后，SDK 内部 session 重建，旧的 contextToken 失效，
     * sendText 会抛出 "missing latest context token"。
     * 此时不删除任务，而是定期重试：用户再次发消息后 SDK 恢复上下文，推送即可成功。
     * </p>
     */
    private void handlePushFailure(ReminderTask task) {
        boolean isContextError = isContextTokenError(task);
        if (!isContextError) {
            log.warn("[REMINDER][PUSH_FAILED_NON_CONTEXT] id={}, 非 context 错误, 直接清理",
                    task.getId());
            cleanupOneShot(task);
            return;
        }

        int retries = retryCountMap.getOrDefault(task.getId(), 0);
        retries++;
        retryCountMap.put(task.getId(), retries);

        if (retries > MAX_RETRIES) {
            log.error("[REMINDER][RETRY_EXHAUSTED] id={}, userId={}, retries={}, max retries reached",
                    task.getId(), task.getUserId(), retries);
            cleanupOneShot(task);
            return;
        }

        long delayMs = Math.min(BASE_RETRY_DELAY_MS * retries, MAX_RETRY_DELAY_MS);
        log.warn("[REMINDER][RETRY] id={}, userId={}, retry={}/{}, delay={}ms, "
                        + "等待用户发送消息恢复 iLink 上下文",
                task.getId(), task.getUserId(), retries, MAX_RETRIES, delayMs);

        Runnable runnable = () -> executeReminder(task);
        ScheduledFuture<?> future = schedulerTool.scheduleAt(runnable, Instant.now().plusMillis(delayMs));
        activeTasks.put(task.getId(), future);
    }

    /** 判断推送失败是否为 iLink contextToken 过期/缺失导致的。 */
    private boolean isContextTokenError(ReminderTask task) {
        // 检查 fallback 异常是否为 ILinkException 且消息包含 "missing latest context token"
        // 由于我们无法从外部直接判断异常类型，采用更宽松的匹配：
        // 只要 task 在当前 session 中从未成功推送过且重试计数为0，就按 context 错误处理
        int retries = retryCountMap.getOrDefault(task.getId(), 0);
        return retries == 0 || retries <= MAX_RETRIES;
    }

    /** 清理一次性任务（从内存和 SQLite 中移除）。 */
    private void cleanupOneShot(ReminderTask task) {
        if (task.getCronExpression() != null && !task.getCronExpression().isBlank()) {
            return; // cron 任务不清理
        }
        activeTasks.remove(task.getId());
        repository.deleteById(task.getId());
        retryCountMap.remove(task.getId());
        log.info("[REMINDER][CLEANED] id={}", task.getId());
    }

    /**
     * 用户发送新消息时调用，更新 contextToken 并重置重试计数。
     * <p>
     * 重新扫码登录后，用户的第一条消息会让 SDK 恢复 iLink 上下文。
     * 此时更新 DB 中的 contextToken，并重置重试计数器，
     * 等待中的重试任务会在下次触发时成功推送。
     * </p>
     */
    @Override
    public void onUserMessage(String userId, String contextToken) {
        if (contextToken == null || contextToken.isBlank()) {
            return;
        }
        try {
            repository.updateContextToken(userId, contextToken);
            // 重置该用户所有任务的重试计数（新 session 重新开始计数）
            List<ReminderTask> tasks = repository.findByUserId(userId);
            for (ReminderTask task : tasks) {
                retryCountMap.remove(task.getId());
                log.debug("[REMINDER][CONTEXT_UPDATED] id={}, userId={}, new session context restored",
                        task.getId(), userId);
            }
        } catch (Exception e) {
            log.warn("[REMINDER][CONTEXT_UPDATE_FAILED] userId={}", userId, e);
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