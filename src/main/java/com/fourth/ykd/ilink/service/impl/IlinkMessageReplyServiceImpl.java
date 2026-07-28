package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ilink.client.IlinkClientManager;
import com.fourth.ykd.ilink.service.IlinkMessageReplyService;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.IOException;
import java.util.concurrent.*;
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
    private final IlinkClientManager clientManager;
    private final ConcurrentMap<String, CompletableFuture<Void>> replyChains = new ConcurrentHashMap<>();

    /** 注入回复处理、发送和串行执行所需组件。 */
    public IlinkMessageReplyServiceImpl(IlinkReplyProcessor replyProcessor, IlinkReplySender replySender,
            @Qualifier("iLinkReplyExecutor") Executor replyExecutor, IlinkClientManager clientManager) {
        this.replyProcessor = replyProcessor; this.replySender = replySender; this.replyExecutor = replyExecutor;
        this.clientManager = clientManager;
    }
    /** 提交文字消息。 */
    @Override public void submit(ILinkClient client, String userId, String contextToken, String userText) {
        if (StringUtils.hasText(userId) && StringUtils.hasText(userText)) enqueue(userId, () -> reply(client, userId, contextToken, userText.trim()), () -> { });
    }
    /** 提交图片确认任务。 */
    @Override public void submitImageReceived(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId)) enqueue(userId, () -> replyImageReceived(client, userId), () -> { });
    }
    /** 提交已识别文本的语音消息。 */
    @Override public void submitVoice(ILinkClient client, String userId, String contextToken, String voiceText) {
        if (StringUtils.hasText(userId) && StringUtils.hasText(voiceText)) enqueue(userId, () -> replyVoice(client, userId, contextToken, voiceText.trim()), () -> replySender.sendVoiceReplyFailureMessage(client, userId));
    }
    /** 提交语音识别失败提示。 */
    @Override public void submitVoiceRecognitionFailed(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId)) enqueue(userId, () -> replySender.sendVoiceRecognitionFailureMessage(client, userId), () -> replySender.sendVoiceRecognitionFailureMessage(client, userId));
    }

    @Override
    public void reply(String userId, String content) {
        clientManager.findClient().ifPresent(client -> {
            try {
                client.sendText(userId, content);
                log.info("[iLink][REMINDER_PUSHED] userId={}", userId);
            } catch (IOException e) {
                log.error("[iLink][REMINDER_PUSH_FAILED] userId={}", userId, e);
            }
        });
    }

    /** 将任务串接到同一用户已有任务之后。 */
    private void enqueue(String userId, Runnable task, Runnable rejectedTask) {
        try {
            CompletableFuture<Void> current = replyChains.compute(userId, (key, previous) ->
                    (previous == null ? CompletableFuture.completedFuture(null) : previous.handle((value, error) -> null)).thenRunAsync(task, replyExecutor));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
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
    private void reply(ILinkClient client, String userId, String contextToken, String userText) {
        long startedAt = System.currentTimeMillis(); replySender.startTypingQuietly(client, userId);
        try { replySender.sendTextModeReply(client, userId, replyProcessor.process(userId, userText, contextToken, false), startedAt); }
        catch (Exception exception) { log.error("[iLink][REPLY_FAILED] userId={}", userId, exception); replySender.sendFailureMessage(client, userId); }
        finally { replySender.stopTypingQuietly(client, userId); }
    }
    /** 处理并发送语音回复。 */
    private void replyVoice(ILinkClient client, String userId, String contextToken, String userText) {
        long startedAt = System.currentTimeMillis(); replySender.startTypingQuietly(client, userId);
        try { replySender.sendVoiceModeReply(client, userId, replyProcessor.process(userId, userText, contextToken, true), startedAt); }
        catch (Exception exception) { log.error("[iLink][VOICE_REPLY_FAILED] userId={}", userId, exception); replySender.sendVoiceReplyFailureMessage(client, userId); }
        finally { replySender.stopTypingQuietly(client, userId); }
    }
}
