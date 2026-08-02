package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ilink.service.IlinkMessageReplyService;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 维护同一用户的回复顺序，并管理回复生命周期。 */
@Slf4j
@Service
public class IlinkMessageReplyServiceImpl implements IlinkMessageReplyService {
    private final IlinkReplyProcessor replyProcessor;
    private final IlinkReplySender replySender;
    private final Executor replyExecutor;
    private final AiChatService aiChatService;
    private final Executor memoryManagementExecutor;
    private final ConcurrentMap<String, CompletableFuture<Void>> replyChains = new ConcurrentHashMap<>();

    /** 注入回复处理、发送和串行执行所需组件。 */
    public IlinkMessageReplyServiceImpl(IlinkReplyProcessor replyProcessor, IlinkReplySender replySender,
            @Qualifier("iLinkReplyExecutor") Executor replyExecutor,
            AiChatService aiChatService,
            @Qualifier("memoryManagementExecutor") Executor memoryManagementExecutor) {
        this.replyProcessor = replyProcessor; this.replySender = replySender; this.replyExecutor = replyExecutor;
        this.aiChatService = aiChatService; this.memoryManagementExecutor = memoryManagementExecutor;
    }
    /** 提交文字消息。 */
    @Override public void submit(ILinkClient client, String userId, String userText) {
        if (StringUtils.hasText(userId) && StringUtils.hasText(userText)) enqueue(userId, () -> reply(client, userId, userText.trim()), () -> { }, () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交图片确认任务。 */
    @Override public void submitImageReceived(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId)) enqueue(userId, () -> replyImageReceived(client, userId), () -> { }, () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交已识别文本的语音消息。 */
    @Override public void submitVoice(ILinkClient client, String userId, String voiceText) {
        if (StringUtils.hasText(userId) && StringUtils.hasText(voiceText)) enqueue(userId, () -> replyVoice(client, userId, voiceText.trim()), () -> replySender.sendVoiceReplyFailureMessage(client, userId), () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交语音识别失败提示。 */
    @Override public void submitVoiceRecognitionFailed(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId)) enqueue(userId, () -> replySender.sendVoiceRecognitionFailureMessage(client, userId), () -> replySender.sendVoiceRecognitionFailureMessage(client, userId), () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 将任务串接到同一用户已有任务之后。 */
    private void enqueue(String userId, Runnable task, Runnable rejectedTask, Runnable queuedFeedback) {
        long enqueuedAt = System.currentTimeMillis();
        log.info("[iLink][REPLY_ENQUEUED] userId={}", userId);
        AtomicBoolean hasPendingPredecessor = new AtomicBoolean(false);

        try {
            CompletableFuture<Void> current = replyChains.compute(userId, (key, previous) -> {
                hasPendingPredecessor.set(previous != null && !previous.isDone());
                CompletableFuture<Void> predecessor = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.handle((value, error) -> null);

                return predecessor.thenRunAsync(() -> {
                    long queueWaitMs = System.currentTimeMillis() - enqueuedAt;
                    log.info("[iLink][REPLY_STARTED] userId={}, queueWaitMs={}",
                            userId, queueWaitMs);
                    task.run();
                }, replyExecutor);
            });
            current.whenComplete((value, error) -> replyChains.remove(userId, current));

            if (hasPendingPredecessor.get()) {
                queuedFeedback.run();
            }
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][REPLY_REJECTED] userId={}", userId, exception);
            rejectedTask.run();
        }
    }
    /** 写入图片记忆后发送确认语。 */
    private void replyImageReceived(ILinkClient client, String userId) {
        try { replyProcessor.saveReceivedImageMemory(userId); }
        catch (RuntimeException exception) { log.error("[iLink][IMAGE_MEMORY_SAVE_FAILED] userId={}", userId, exception); }
        replySender.sendImageReceivedConfirmation(client, userId);
    }
    /** 处理并发送文字回复。 */
    private void reply(ILinkClient client, String userId, String userText) {
        long startedAt = System.currentTimeMillis(); replySender.startTypingQuietly(client, userId);
        try {
            IlinkReplyProcessor.ReplyResult replyResult = replyProcessor.process(userId, userText, false);
            replySender.sendTextModeReply(client, userId, replyResult, startedAt);
            submitMemoryManagementIfNeeded(replyResult, userId, userText, client);
        }
        catch (Exception exception) { log.error("[iLink][REPLY_FAILED] userId={}", userId, exception); replySender.sendFailureMessage(client, userId); }
        finally { replySender.stopTypingQuietly(client, userId); }
    }
    private void submitMemoryManagementIfNeeded(
            IlinkReplyProcessor.ReplyResult replyResult,
            String userId,
            String userText,
            ILinkClient client
    ) {
        if (replyResult.intent() == UserIntent.MEMORY_MANAGE) {
            submitMemoryManagement(userId, userText, client);
        }
    }
    /** 将长期记忆管理放入既有记忆线程池，避免阻塞当前微信回复。 */
    private void submitMemoryManagement(String userId, String userText, ILinkClient client) {
        try {
            CompletableFuture.runAsync(() -> executeMemoryManagement(userId, userText, client), memoryManagementExecutor);
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][MEMORY_MANAGEMENT_REJECTED] userId={}", userId, exception);
            enqueue(userId, () -> replySender.sendFailureMessage(client, userId), () -> replySender.sendFailureMessage(client, userId), () -> { });
        }
    }
    /** 保留原有记忆形成和结果生成逻辑，完成后进入同一用户回复队列发送。 */
    private void executeMemoryManagement(String userId, String userText, ILinkClient client) {
        try {
            AiChatResponse response = aiChatService.manageMemory(userId, userText);
            enqueue(userId, () -> sendMemoryManagementCompletion(client, userId, response.reply()), () -> replySender.sendFailureMessage(client, userId), () -> { });
        } catch (RuntimeException exception) {
            log.error("[iLink][MEMORY_MANAGEMENT_FAILED] userId={}", userId, exception);
            enqueue(userId, () -> replySender.sendFailureMessage(client, userId), () -> replySender.sendFailureMessage(client, userId), () -> { });
        }
    }
    /** 发送长期记忆管理的最终结果。 */
    private void sendMemoryManagementCompletion(ILinkClient client, String userId, String answer) {
        long startedAt = System.currentTimeMillis(); replySender.startTypingQuietly(client, userId);
        try { replySender.sendTextModeReply(client, userId, IlinkReplyProcessor.ReplyResult.text(UserIntent.MEMORY_MANAGE, answer, null), startedAt); }
        catch (Exception exception) { log.error("[iLink][MEMORY_MANAGEMENT_COMPLETION_FAILED] userId={}", userId, exception); replySender.sendFailureMessage(client, userId); }
        finally { replySender.stopTypingQuietly(client, userId); }
    }
    /** 处理并发送语音回复。 */
    private void replyVoice(ILinkClient client, String userId, String userText) {
        long startedAt = System.currentTimeMillis(); replySender.startTypingQuietly(client, userId);
        try {
            IlinkReplyProcessor.ReplyResult replyResult =
                    replyProcessor.process(userId, userText, true);
            replySender.sendVoiceModeReply(client, userId, replyResult, startedAt);
            submitMemoryManagementIfNeeded(replyResult, userId, userText, client);
        }
        catch (Exception exception) { log.error("[iLink][VOICE_REPLY_FAILED] userId={}", userId, exception); replySender.sendVoiceReplyFailureMessage(client, userId); }
        finally { replySender.stopTypingQuietly(client, userId); }
    }
}
