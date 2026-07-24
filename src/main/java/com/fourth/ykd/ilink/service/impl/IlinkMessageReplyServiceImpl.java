package com.fourth.ykd.ilink.service.impl;

import com.fourth.ykd.ai.dto.GeneratedAudio;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.GeneratedDocument;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.routing.DeepSeekIntentRouter;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ai.service.AudioSynthesisService;
import com.fourth.ykd.ai.service.ImageContextService;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.ai.utils.FileGenerationTool;
import com.fourth.ykd.ilink.service.IlinkMessageReplyService;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class IlinkMessageReplyServiceImpl implements IlinkMessageReplyService {

    private static final String IMAGE_MEMORY_PROMPT = """
            请识别这张图片，并生成供后续多轮聊天使用的中文图片记忆。
            只描述图片中确实可见的内容；优先说明主体、人物或物体、场景、明显颜色、
            相对位置、可辨识文字和关键细节。
            不确定的内容明确说明无法确认；不要寒暄、不要提问、不要编造。
            """;

    private final AiChatService aiChatService;
    private final AudioSynthesisService audioSynthesisService;
    private final DeepSeekIntentRouter intentRouter;
    private final ImageGenerationService imageGenerationService;
    private final ImageReferenceGenerationService imageReferenceGenerationService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageContextService imageContextService;
    private final FileGenerationTool fileGenerationTool;
    private final ChatMemory chatMemory;
    private final Executor replyExecutor;
    private final ConcurrentMap<String, CompletableFuture<Void>> replyChains = new ConcurrentHashMap<>();

    public IlinkMessageReplyServiceImpl(
            AiChatService aiChatService,
            AudioSynthesisService audioSynthesisService,
            DeepSeekIntentRouter intentRouter,
            ImageGenerationService imageGenerationService,
            ImageReferenceGenerationService imageReferenceGenerationService,
            ImageUnderstandingService imageUnderstandingService,
            ImageContextService imageContextService,
            FileGenerationTool fileGenerationTool,
            ChatMemory chatMemory,
            @Qualifier("iLinkReplyExecutor") Executor replyExecutor
    ) {
        this.aiChatService = aiChatService;
        this.audioSynthesisService = audioSynthesisService;
        this.intentRouter = intentRouter;
        this.imageGenerationService = imageGenerationService;
        this.imageReferenceGenerationService = imageReferenceGenerationService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.imageContextService = imageContextService;
        this.fileGenerationTool = fileGenerationTool;
        this.chatMemory = chatMemory;
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
            CompletableFuture<Void> current = enqueue(userId, () -> reply(client, userId, text));
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
            CompletableFuture<Void> current = enqueue(userId, () -> replyImageReceived(client, userId));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][REPLY_REJECTED] userId={}", userId);
        }
    }

    @Override
    public void submitVoice(ILinkClient client, String userId, String voiceText) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(voiceText)) {
            return;
        }
        String text = voiceText.trim();
        log.info("[iLink][VOICE_QUEUED] userId={}, text={}", userId, text);
        try {
            CompletableFuture<Void> current = enqueue(userId, () -> replyVoice(client, userId, text));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][VOICE_REPLY_REJECTED] userId={}, reason={}", userId, exception.getMessage());
            sendVoiceReplyFailureMessage(client, userId);
        }
    }

    @Override
    public void submitVoiceRecognitionFailed(ILinkClient client, String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }
        log.info("[iLink][VOICE_RECOGNITION_FAILURE_QUEUED] userId={}", userId);
        try {
            CompletableFuture<Void> current = enqueue(userId, () -> sendVoiceRecognitionFailureMessage(client, userId));
            current.whenComplete((value, error) -> replyChains.remove(userId, current));
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][VOICE_RECOGNITION_FAILURE_REPLY_REJECTED] userId={}, reason={}",
                    userId, exception.getMessage());
            sendVoiceRecognitionFailureMessage(client, userId);
        }
    }

    private CompletableFuture<Void> enqueue(String userId, Runnable task) {
        return replyChains.compute(userId, (key, previous) ->
                (previous == null ? CompletableFuture.completedFuture(null) : previous.handle((value, error) -> null))
                        .thenRunAsync(task, replyExecutor));
    }

    private void replyImageReceived(ILinkClient client, String userId) {
        try {
            saveReceivedImageMemory(userId);
        } catch (RuntimeException exception) {
            // 图片记忆写入失败不影响用户收到固定确认语。
            log.error("[iLink][IMAGE_MEMORY_SAVE_FAILED] userId={}", userId, exception);
        }

        try {
            client.sendTextWithTyping(userId, "已经看到您的图片啦，您想了解什么呢？", 800);
            log.info("[iLink][IMAGE_CONTEXT_REPLIED] toUserId={}", userId);
        } catch (IOException exception) {
            log.warn("[iLink][IMAGE_CONTEXT_REPLY_FAILED] userId={}, reason={}",
                    userId, exception.getMessage());
        }
    }

    /**
     * 后台识图后仅写入聊天记忆，不直接把识图结果发送给用户。
     */
    private void saveReceivedImageMemory(String userId) {
        PendingUserImage image = imageContextService.findActive(userId)
                .orElseThrow(() -> new IllegalStateException("当前图片上下文不存在"));
        saveImageMemory(userId, image, "用户此前发送了一张图片");
    }

    /**
     * 机器人生成图片后，识别实际生成结果并写入聊天记忆。
     */
    private void saveGeneratedImageMemoryQuietly(String userId, GeneratedImage generatedImage, String imageSource) {
        try {
            PendingUserImage image = new PendingUserImage(
                    generatedImage.bytes(),
                    generatedImage.contentType(),
                    Instant.now()
            );
            saveImageMemory(userId, image, imageSource);
        } catch (RuntimeException exception) {
            // 图片记忆写入失败不影响生成图片发送给用户。
            log.error("[iLink][GENERATED_IMAGE_MEMORY_SAVE_FAILED] userId={}", userId, exception);
        }
    }

    private void saveImageMemory(String userId, PendingUserImage image, String imageSource) {
        String imageSummary = imageUnderstandingService.understand(image, IMAGE_MEMORY_PROMPT);
        String memoryContent = """
                【图片识别记忆】
                %s，后台识别结果如下：
                %s
                """.formatted(imageSource, imageSummary);

        // userId 与 AiChatService.chat(userId, userText) 使用同一个会话标识。
        chatMemory.add(userId, List.of(new AssistantMessage(memoryContent)));
        log.info("[iLink][IMAGE_MEMORY_SAVED] userId={}, source={}, summary={}",
                userId, imageSource, formatAnswerForLog(imageSummary));
    }
    private void reply(ILinkClient client, String userId, String userText) {
        long startedAt = System.currentTimeMillis();
        startTypingQuietly(client, userId);
        try {
            ReplyResult result = buildReplyResult(userId, userText, false);
            sendTextModeReply(client, userId, result, startedAt);
        } catch (Exception exception) {
            log.error("[iLink][REPLY_FAILED] userId={}, elapsedMs={}",
                    userId, System.currentTimeMillis() - startedAt, exception);
            sendFailureMessage(client, userId);
        } finally {
            stopTypingQuietly(client, userId);
        }
    }

    private void replyVoice(ILinkClient client, String userId, String userText) {
        long startedAt = System.currentTimeMillis();
        startTypingQuietly(client, userId);
        try {
            ReplyResult result = buildReplyResult(userId, userText, true);
            sendVoiceModeReply(client, userId, result, startedAt);
        } catch (Exception exception) {
            log.error("[iLink][VOICE_REPLY_FAILED] userId={}, elapsedMs={}",
                    userId, System.currentTimeMillis() - startedAt, exception);
            sendVoiceReplyFailureMessage(client, userId);
        } finally {
            stopTypingQuietly(client, userId);
        }
    }

    private ReplyResult buildReplyResult(String userId, String userText, boolean voiceMode) {

        Optional<PendingUserImage> pendingImage = imageContextService.findActive(userId);

        UserIntent intent = intentRouter.route(userText, pendingImage.isPresent());
        log.info("[iLink][{}] userId={}, intent={}, hasPendingImage={}",
                voiceMode ? "VOICE_ROUTED" : "ROUTED", userId, intent, pendingImage.isPresent());

        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_UNDERSTAND) {
            log.info("[iLink][IMAGE_UNDERSTANDING] userId={}, question={}", userId, userText);
            String answer = imageUnderstandingService.understand(pendingImage.get(), userText);
            return ReplyResult.text(intent, answer, pendingImage.get(), "IMAGE_UNDERSTAND");
        }
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_EDIT) {
            log.info("[iLink][IMAGE_EDITING] userId={}, instruction={}", userId, userText);
            GeneratedImage image = imageReferenceGenerationService.generate(pendingImage.get(), userText);
            saveGeneratedImageMemoryQuietly(userId, image, "机器人此前根据用户要求编辑并生成了一张图片");
            return ReplyResult.image(intent, image, pendingImage.get(), "IMAGE_EDIT");
        }
        if (intent == UserIntent.IMAGE_GENERATE) {
            GeneratedImage image = imageGenerationService.generate(userText);
            saveGeneratedImageMemoryQuietly(userId, image, "机器人此前根据用户请求生成了一张图片");
            return ReplyResult.image(intent, image, null, null);
        }

        if (intent == UserIntent.FILE_GENERATE) {
            List<GeneratedDocument> documents = fileGenerationTool.generate(userId, userText);
            return ReplyResult.documents(intent, documents, pendingImage.orElse(null), "FILE_GENERATE");
        }

        String answer = aiChatService.chat(userId, userText).reply();
        return ReplyResult.text(intent, answer, pendingImage.orElse(null), voiceMode ? "VOICE_TEXT" : "TEXT");
    }

    private void sendTextModeReply(ILinkClient client, String userId, ReplyResult result, long startedAt)
            throws IOException {
        if (result.type() == ReplyResultType.IMAGE) {
            sendImageReply(client, userId, result, startedAt);
            return;
        }
        if (result.type() == ReplyResultType.DOCUMENT) {
            sendDocumentReply(client, userId, result, startedAt);
            return;
        }

        client.sendText(userId, result.answer());
        clearImageContextIfNeeded(userId, result);
        if (result.intent() == UserIntent.IMAGE_UNDERSTAND) {
            log.info("[iLink][IMAGE_UNDERSTOOD] toUserId={}, answer={}, elapsedMs={}",
                    userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
            return;
        }
        log.info("[iLink][REPLIED] toUserId={}, answer={}, elapsedMs={}",
                userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
    }

    private void sendVoiceModeReply(ILinkClient client, String userId, ReplyResult result, long startedAt)
            throws IOException {
        if (result.type() == ReplyResultType.IMAGE) {
            sendImageReply(client, userId, result, startedAt);
            return;
        }

        AudioReplyResult audioReplyResult = sendAudioAnswer(client, userId, result.answer(), startedAt);
        clearImageContextIfNeeded(userId, result);
        if (result.intent() == UserIntent.IMAGE_UNDERSTAND) {
            log.info("[iLink][IMAGE_UNDERSTOOD] toUserId={}, answer={}, audioReplyResult={}, elapsedMs={}",
                    userId, formatAnswerForLog(result.answer()), audioReplyResult, System.currentTimeMillis() - startedAt);
            return;
        }
        if (audioReplyResult == AudioReplyResult.AUDIO) {
            log.info("[iLink][VOICE_REPLIED] toUserId={}, answer={}, elapsedMs={}",
                    userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
        } else {
            log.info("[iLink][VOICE_REPLIED_WITH_TEXT_FALLBACK] toUserId={}, answer={}, elapsedMs={}",
                    userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
        }
    }

    private void sendDocumentReply(ILinkClient client, String userId, ReplyResult result, long startedAt)
            throws IOException {
        for (GeneratedDocument document : result.documents()) {
            client.sendFile(userId, document.bytes(), document.fileName(), null);
            log.info("[iLink][DOCUMENT_REPLIED] toUserId={}, fileName={}, fileBytes={}, elapsedMs={}",
                    userId, document.fileName(), document.bytes().length, System.currentTimeMillis() - startedAt);
        }
        clearImageContextIfNeeded(userId, result);
    }

    private void sendImageReply(ILinkClient client, String userId, ReplyResult result, long startedAt)
            throws IOException {
        GeneratedImage image = result.image();
        client.sendImage(userId, image.bytes(), image.fileName(), null);
        clearImageContextIfNeeded(userId, result);
        if (result.intent() == UserIntent.IMAGE_EDIT) {
            log.info("[iLink][IMAGE_EDITED] toUserId={}, imageBytes={}, elapsedMs={}",
                    userId, image.bytes().length, System.currentTimeMillis() - startedAt);
            return;
        }
        log.info("[iLink][IMAGE_REPLIED] toUserId={}, imageBytes={}, elapsedMs={}",
                userId, image.bytes().length, System.currentTimeMillis() - startedAt);
    }

    private void clearImageContextIfNeeded(String userId, ReplyResult result) {
        if (result.imageToClear() == null) {
            return;
        }
        imageContextService.remove(userId, result.imageToClear());
        log.info("[iLink][IMAGE_CONTEXT_CLEARED] userId={}, reason={}", userId, result.clearReason());
    }

    private AudioReplyResult sendAudioAnswer(ILinkClient client, String userId, String answer, long startedAt)
            throws IOException {
        try {
            GeneratedAudio audio = audioSynthesisService.synthesize(answer);
            client.sendFile(userId, audio.bytes(), audio.fileName(), null);
            log.info("[iLink][VOICE_AUDIO_REPLIED] toUserId={}, audioBytes={}, elapsedMs={}",
                    userId, audio.bytes().length, System.currentTimeMillis() - startedAt);
            return AudioReplyResult.AUDIO;
        } catch (Exception exception) {
            log.warn("[iLink][VOICE_AUDIO_REPLY_FAILED] userId={}, reason={}", userId, exception.getMessage());
            client.sendText(userId, "语音回复生成失败了，我先用文字回复您：" + answer);
            log.info("[iLink][VOICE_TEXT_FALLBACK_REPLIED] toUserId={}, answer={}",
                    userId, formatAnswerForLog(answer));
            return AudioReplyResult.TEXT_FALLBACK;
        }
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

    private void sendVoiceRecognitionFailureMessage(ILinkClient client, String userId) {
        try {
            client.sendText(userId, "这段语音暂时没有识别出文字，请重新发一遍或改用文字。");
            log.info("[iLink][VOICE_RECOGNITION_FAILURE_REPLIED] toUserId={}", userId);
        } catch (IOException exception) {
            log.warn("[iLink][VOICE_RECOGNITION_FAILURE_MESSAGE_SEND_FAILED] userId={}, reason={}",
                    userId, exception.getMessage());
        }
    }

    private void sendVoiceReplyFailureMessage(ILinkClient client, String userId) {
        try {
            client.sendText(userId, "语音回复处理失败了，请稍后再试或改用文字。");
            log.info("[iLink][VOICE_REPLY_FAILURE_REPLIED] toUserId={}", userId);
        } catch (IOException exception) {
            log.warn("[iLink][VOICE_REPLY_FAILURE_MESSAGE_SEND_FAILED] userId={}, reason={}",
                    userId, exception.getMessage());
        }
    }

    private enum ReplyResultType {
        TEXT,
        IMAGE,
        DOCUMENT
    }

    private enum AudioReplyResult {
        AUDIO,
        TEXT_FALLBACK
    }

    private record ReplyResult(
            ReplyResultType type,
            UserIntent intent,
            String answer,
            GeneratedImage image,
            List<GeneratedDocument> documents,
            PendingUserImage imageToClear,
            String clearReason
    ) {
        private static ReplyResult text(UserIntent intent, String answer, PendingUserImage imageToClear,
                String clearReason) {
            return new ReplyResult(ReplyResultType.TEXT, intent, answer, null, null, imageToClear, clearReason);
        }

        private static ReplyResult image(UserIntent intent, GeneratedImage image, PendingUserImage imageToClear,
                String clearReason) {
            return new ReplyResult(ReplyResultType.IMAGE, intent, null, image, null, imageToClear, clearReason);
        }

        private static ReplyResult documents(UserIntent intent, List<GeneratedDocument> documents,
                PendingUserImage imageToClear, String clearReason) {
            return new ReplyResult(ReplyResultType.DOCUMENT, intent, null, null, documents, imageToClear, clearReason);
        }
    }
}