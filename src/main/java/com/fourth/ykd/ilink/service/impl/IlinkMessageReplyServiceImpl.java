package com.fourth.ykd.ilink.service.impl;

import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.routing.DeepSeekIntentRouter;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ai.service.ImageContextService;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.ilink.service.IlinkMessageReplyService;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class IlinkMessageReplyServiceImpl implements IlinkMessageReplyService {

    private final AiChatService aiChatService;
    private final DeepSeekIntentRouter intentRouter;
    private final ImageGenerationService imageGenerationService;
    private final ImageReferenceGenerationService imageReferenceGenerationService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageContextService imageContextService;
    private final Executor replyExecutor;
    private final ConcurrentMap<String, CompletableFuture<Void>> replyChains = new ConcurrentHashMap<>();

    public IlinkMessageReplyServiceImpl(
            AiChatService aiChatService,
            DeepSeekIntentRouter intentRouter,
            ImageGenerationService imageGenerationService,
            ImageReferenceGenerationService imageReferenceGenerationService,
            ImageUnderstandingService imageUnderstandingService,
            ImageContextService imageContextService,
            @Qualifier("iLinkReplyExecutor") Executor replyExecutor
    ) {
        this.aiChatService = aiChatService;
        this.intentRouter = intentRouter;
        this.imageGenerationService = imageGenerationService;
        this.imageReferenceGenerationService = imageReferenceGenerationService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.imageContextService = imageContextService;
        this.replyExecutor = replyExecutor;
    }

    @Override
    public void submit(ILinkClient client, String userId, String userText) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(userText)) {
            return;
        }
        String text = userText.trim();
        log.info("[iLink][QUEUED] userId={}, text={}", userId, text);
        try {
            CompletableFuture<Void> current = replyChains.compute(userId, (key, previous) ->
                    (previous == null ? CompletableFuture.completedFuture(null) : previous.handle((value, error) -> null))
                            .thenRunAsync(() -> reply(client, userId, text), replyExecutor));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][REPLY_REJECTED] userId={}", userId);
        }
    }

    @Override
    public void submitImageReceived(ILinkClient client, String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        log.info("[iLink][IMAGE_CONTEXT_QUEUED] userId={}", userId);
        try {
            CompletableFuture<Void> current = replyChains.compute(userId, (key, previous) ->
                    (previous == null ? CompletableFuture.completedFuture(null) : previous.handle((value, error) -> null))
                            .thenRunAsync(() -> replyImageReceived(client, userId), replyExecutor));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][REPLY_REJECTED] userId={}", userId);
        }
    }

    private void replyImageReceived(ILinkClient client, String userId) {
        try {
            client.sendTextWithTyping(userId, "已经看到您的图片啦，您想了解什么呢？", 800);
            log.info("[iLink][IMAGE_CONTEXT_REPLIED] toUserId={}, answer={}",
                    userId, "已经看到您的图片啦，您想了解什么呢？");
        } catch (IOException exception) {
            log.warn("[iLink][IMAGE_CONTEXT_REPLY_FAILED] userId={}, reason={}",
                    userId, exception.getMessage());
        }
    }
    private void reply(ILinkClient client, String userId, String userText) {
        long startedAt = System.currentTimeMillis();
        startTypingQuietly(client, userId);
        try {
            Optional<PendingUserImage> pendingImage = imageContextService.findActive(userId);
            UserIntent intent = intentRouter.route(userText, pendingImage.isPresent());
            log.info("[iLink][ROUTED] userId={}, intent={}, hasPendingImage={}",
                    userId, intent, pendingImage.isPresent());

            if (pendingImage.isPresent() && intent == UserIntent.IMAGE_UNDERSTAND) {
                replyWithImageUnderstanding(client, userId, userText, pendingImage.get(), startedAt);
                return;
            }
            if (pendingImage.isPresent() && intent == UserIntent.IMAGE_EDIT) {
                replyWithImageReferenceGeneration(client, userId, userText, pendingImage.get(), startedAt);
                return;
            }
            if (intent == UserIntent.IMAGE_GENERATE) {
                GeneratedImage image = imageGenerationService.generate(userText);
                client.sendImage(userId, image.bytes(), image.fileName(), null);
                log.info("[iLink][IMAGE_REPLIED] toUserId={}, imageBytes={}, elapsedMs={}",
                        userId, image.bytes().length, System.currentTimeMillis() - startedAt);
                return;
            }

            String answer = aiChatService.chat(userText).reply();
            client.sendText(userId, answer);
            pendingImage.ifPresent(image -> {
                imageContextService.remove(userId, image);
                log.info("[iLink][IMAGE_CONTEXT_CLEARED] userId={}, reason=TEXT", userId);
            });
            log.info("[iLink][REPLIED] toUserId={}, answer={}, elapsedMs={}",
                    userId, formatAnswerForLog(answer), System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            log.error("[iLink][REPLY_FAILED] userId={}, elapsedMs={}",
                    userId, System.currentTimeMillis() - startedAt, exception);
            sendFailureMessage(client, userId);
        } finally {
            stopTypingQuietly(client, userId);
        }
    }

    private void replyWithImageReferenceGeneration(
            ILinkClient client,
            String userId,
            String instruction,
            PendingUserImage image,
            long startedAt
    ) throws IOException {
        log.info("[iLink][IMAGE_EDITING] userId={}, instruction={}", userId, instruction);
        GeneratedImage generatedImage = imageReferenceGenerationService.generate(image, instruction);
        client.sendImage(userId, generatedImage.bytes(), generatedImage.fileName(), null);
        imageContextService.remove(userId, image);
        log.info("[iLink][IMAGE_EDITED] toUserId={}, imageBytes={}, elapsedMs={}",
                userId, generatedImage.bytes().length, System.currentTimeMillis() - startedAt);
    }
    private void replyWithImageUnderstanding(
            ILinkClient client,
            String userId,
            String question,
            PendingUserImage image,
            long startedAt
    ) throws IOException {
        log.info("[iLink][IMAGE_UNDERSTANDING] userId={}, question={}", userId, question);
        String answer = imageUnderstandingService.understand(image, question);
        client.sendText(userId, answer);
        // 仅在模型和发送都成功后清理图片，失败时仍可再次追问。
        imageContextService.remove(userId, image);
        log.info("[iLink][IMAGE_UNDERSTOOD] toUserId={}, answer={}, elapsedMs={}",
                userId, formatAnswerForLog(answer), System.currentTimeMillis() - startedAt);
    }

    private String formatAnswerForLog(String answer) {
        if (answer == null) {
            return "";
        }
        String singleLine = answer.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.length() <= 1_000 ? singleLine : singleLine.substring(0, 1_000) + "...";
    }
    private void startTypingQuietly(ILinkClient client, String userId) {
        try {
            client.startTyping(userId);
            log.info("[iLink][TYPING_STARTED] userId={}", userId);
        } catch (IOException exception) {
            log.warn("[iLink][TYPING_START_FAILED] userId={}, reason={}", userId, exception.getMessage());
        }
    }

    private void stopTypingQuietly(ILinkClient client, String userId) {
        try {
            client.stopTyping(userId);
            log.info("[iLink][TYPING_STOPPED] userId={}", userId);
        } catch (IOException exception) {
            log.warn("[iLink][TYPING_STOP_FAILED] userId={}, reason={}", userId, exception.getMessage());
        }
    }

    private void sendFailureMessage(ILinkClient client, String userId) {
        try {
            client.sendText(userId, "抱歉，刚才处理失败了，请稍后再试。");
        } catch (IOException exception) {
            log.warn("[iLink][FAILURE_MESSAGE_SEND_FAILED] userId={}, reason={}", userId, exception.getMessage());
        }
    }
}