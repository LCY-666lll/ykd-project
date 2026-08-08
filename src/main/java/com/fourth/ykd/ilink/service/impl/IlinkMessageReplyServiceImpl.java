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
    /** 每个 userId 对应一条 CompletableFuture 链：新任务永远接在上一个任务后面 */
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
        if (StringUtils.hasText(userId) && StringUtils.hasText(userText))
            enqueue(userId, () -> reply(client, userId, userText.trim()), () -> { }, () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交图片确认任务。 */
    @Override public void submitImageReceived(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId))
            enqueue(userId, () -> replyImageReceived(client, userId), () -> { }, () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交已识别文本的语音消息。 */
    @Override public void submitVoice(ILinkClient client, String userId, String voiceText) {
        if (StringUtils.hasText(userId) && StringUtils.hasText(voiceText))
            enqueue(userId, () -> replyVoice(client, userId, voiceText.trim()), () -> replySender.sendVoiceReplyFailureMessage(client, userId), () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 提交语音识别失败提示。 */
    @Override public void submitVoiceRecognitionFailed(ILinkClient client, String userId) {
        if (StringUtils.hasText(userId))
            enqueue(userId, () -> replySender.sendVoiceRecognitionFailureMessage(client, userId), () -> replySender.sendVoiceRecognitionFailureMessage(client, userId), () -> replySender.sendQueueWaitingMessage(client, userId));
    }
    /** 将任务串接到同一用户已有任务之后。 */
    private void enqueue(String userId, Runnable task, Runnable rejectedTask, Runnable queuedFeedback) {
        //记录入队时间
        long enqueuedAt = System.currentTimeMillis();
        log.info("[iLink][REPLY_ENQUEUED] userId={}", userId);
        //用 AtomicBoolean，主要不是为了复杂的并发原子操作，而是因为 Lambda 内不能直接修改普通局部变量。
        //把 compute() 内部得出的状态传到外面:记录当前用户入队时，前面是否还有没有完成的任务
        AtomicBoolean hasPendingPredecessor = new AtomicBoolean(false);

        try {
            // compute 是原子操作：同一 userId 并发提交时，只会有一个线程更新这条链
            //compute() 的关键作用是：对同一个 userId，原子完成“读取旧任务、生成新任务、保存新任务”。
            CompletableFuture<Void> current = replyChains.compute(
                    userId, (key, previous) -> {
                // 记录"入队时是否已有未完成的前置任务"，用来决定要不要发排队提示
                hasPendingPredecessor.set(previous != null && !previous.isDone());

                // 前一个任务不存在 → 用已完成 Future 兜底；存在 → 接在它后面
                // handle((value, error) -> null) 的作用：吞掉前一个任务的异常，
                // 防止前一个任务失败导致整条链断裂、后面的任务永远不执行
                CompletableFuture<Void> predecessor = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.handle((value, error) -> null);

                /*在回复线程池里执行真正的任务（模型调用、发送都在这里，不占轮询线程
                等待 predecessor 完成 把当前 task 交给 replyExecutor 在线程池中执行
                thenRunAsync 保证：
                当前任务必须等前一个任务完成。
                当前任务在线程池中执行。
                不占用微信消息接收线程。*/
                return predecessor.thenRunAsync(() -> {
                    long queueWaitMs = System.currentTimeMillis() - enqueuedAt;
                    log.info("[iLink][REPLY_STARTED] userId={}, queueWaitMs={}", userId, queueWaitMs);
                    task.run();
                }, replyExecutor);
            });

            // 当前任务完成后，从 map 里移除自己（remove(key, value) 是条件删除，
            // 只有当前还是链头才删，避免把新入队的任务误删）
            current.whenComplete((value, error) ->
                    replyChains.remove(userId, current));

            // 入队时发现前面还有任务没完成 → 立即给用户发"排队中"提示
            if (hasPendingPredecessor.get()) {
                queuedFeedback.run();
            }
        } catch (RejectedExecutionException exception) {
            // 线程池队列满了，AbortPolicy 抛出的异常在这里接住 → 走失败降级
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
        long startedAt = System.currentTimeMillis();
        replySender.startTypingQuietly(client, userId);
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
    private void submitMemoryManagement(
            String userId, String userText, ILinkClient client) {
        try {
            CompletableFuture.runAsync(() -> executeMemoryManagement(userId, userText, client), memoryManagementExecutor);
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][MEMORY_MANAGEMENT_REJECTED] userId={}", userId, exception);
            /*记忆线程池拒绝任务以后，没有直接发送失败消息。
            而是重新调用：enqueue(...)
            让失败消息回到当前用户的回复队列。
            这样做是为了避免失败提示插到其他回复前面。*/
            enqueue(userId, () -> replySender.sendFailureMessage(client, userId), () -> replySender.sendFailureMessage(client, userId), () -> { });
        }
    }
    /** 保留原有记忆形成和结果生成逻辑，完成后进入同一用户回复队列发送。 */
    private void executeMemoryManagement(
            String userId, String userText, ILinkClient client) {
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
