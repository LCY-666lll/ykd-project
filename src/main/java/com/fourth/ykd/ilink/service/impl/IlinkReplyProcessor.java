package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ai.browser.BrowserTaskService;
import com.fourth.ykd.ai.dto.*;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.routing.*;
import com.fourth.ykd.ai.service.*;
import com.fourth.ykd.ai.utils.FileGenerationTool;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

/** 负责意图路由、业务执行和图片记忆。 */
@Slf4j
@Service
public class IlinkReplyProcessor {
    private static final String IMAGE_MEMORY_PROMPT = """
            请识别这张图片，并生成供后续多轮聊天使用的中文图片记忆。
            只描述图片中确实可见的内容；不确定时明确说明无法确认；不要寒暄、提问或编造。
            """;
    private static final java.util.regex.Pattern HTTP_URL_PATTERN = java.util.regex.Pattern.compile("(?i)https?://\\S+");
    private final AiChatService aiChatService;
    private final DeepSeekIntentRouter intentRouter;
    private final ImageGenerationService imageGenerationService;
    private final ImageReferenceGenerationService imageReferenceGenerationService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageContextService imageContextService;
    private final FileGenerationTool fileGenerationTool;
    private final ChatMemory chatMemory;
    private final SqliteChatMessageRepository sqliteChatMessageRepository;
    private final Executor imageMemoryExecutor;
    private final BrowserTaskService browserTaskService;
    /** 注入现有的回复处理依赖。 */
    public IlinkReplyProcessor(AiChatService aiChatService, DeepSeekIntentRouter intentRouter,
                               ImageGenerationService imageGenerationService, ImageReferenceGenerationService imageReferenceGenerationService,
                               ImageUnderstandingService imageUnderstandingService, ImageContextService imageContextService,
                               FileGenerationTool fileGenerationTool, ChatMemory chatMemory,
                               SqliteChatMessageRepository sqliteChatMessageRepository,
                               @Qualifier("memoryExecutor") Executor imageMemoryExecutor,
                               BrowserTaskService browserTaskService) {
        this.aiChatService = aiChatService; this.intentRouter = intentRouter;
        this.imageGenerationService = imageGenerationService;
        this.imageReferenceGenerationService = imageReferenceGenerationService;
        this.imageUnderstandingService = imageUnderstandingService; this.imageContextService = imageContextService;
        this.fileGenerationTool = fileGenerationTool; this.chatMemory = chatMemory;
        this.sqliteChatMessageRepository = sqliteChatMessageRepository;
        this.imageMemoryExecutor = imageMemoryExecutor;
        this.browserTaskService = browserTaskService;
    }

    /** 按现有意图执行业务，并产出待发送结果。 */
    public ReplyResult process(String userId, String userText, boolean voiceMode) {
        Optional<PendingUserImage> pendingImage = imageContextService.findActive(userId);
        UserIntent intent = intentRouter.route(userId, userText, pendingImage.isPresent());
        if (pendingImage.isEmpty() && (intent == UserIntent.IMAGE_EDIT || intent == UserIntent.IMAGE_UNDERSTAND)) {
            log.warn("[iLink][IMAGE_CONTEXT_MISSING] userId={}, intent={}", userId, intent);
            intent = UserIntent.TEXT;
        }
        log.info("[iLink][{}] userId={}, intent={}, hasPendingImage={}",
                voiceMode ? "VOICE_ROUTED" : "ROUTED", userId, intent, pendingImage.isPresent());
        saveSpecialFlowUserMessage(userId, userText, intent);
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_UNDERSTAND) {
            log.info("[AI][IMAGE_UNDERSTAND][START] userId={}", userId);
            String answer = imageUnderstandingService.understand(pendingImage.get(), userText);
            log.info("[AI][IMAGE_UNDERSTAND][SUCCESS] userId={}, answerLength={}", userId, answer.length());
            return ReplyResult.text(intent, answer, null);
        }
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_EDIT) {
            log.info("[AI][IMAGE_EDIT][START] userId={}", userId);
            GeneratedImage image = imageReferenceGenerationService.generate(pendingImage.get(), userText);
            saveGeneratedImageMemoryAsync(userId, image, "机器人此前根据用户要求编辑并生成了一张图片");
            log.info("[AI][IMAGE_EDIT][SUCCESS] userId={}, imageBytes={}", userId, image.bytes().length);
            return ReplyResult.image(intent, image, pendingImage.get());
        }
        if (intent == UserIntent.IMAGE_GENERATE) {
            log.info("[AI][IMAGE_GENERATE][START] userId={}", userId);
            String imagePrompt = aiChatService.prepareImagePrompt(userId, userText);
            log.info("[AI][IMAGE_GENERATE][PROMPT] userId={}, promptLength={}",
                    userId, imagePrompt.length());
            GeneratedImage image = imageGenerationService.generate(imagePrompt);
            saveGeneratedImageMemoryAsync(userId, image, "机器人此前根据用户请求生成了一张图片");
            log.info("[AI][IMAGE_GENERATE][SUCCESS] userId={}, imageBytes={}", userId, image.bytes().length);
            return ReplyResult.image(intent, image, null);
        }
        if (intent == UserIntent.BROWSER_TASK) {
            log.info("[AI][BROWSER_TASK][START] userId={}", userId);
            String browserRequest = resolveBrowserRequest(userId, userText);
            String answer = browserTaskService.execute(userId, browserRequest);
            chatMemory.add(userId, List.of(new AssistantMessage(answer)));
            sqliteChatMessageRepository.save(userId, PersistedChatMessage.Role.ASSISTANT, answer);
            sqliteChatMessageRepository.softDeleteOldMessages(userId, 100);
            log.info("[AI][BROWSER_TASK][FINISHED] userId={}, answerLength={}",
                    userId, answer.length());
            return ReplyResult.text(intent, answer, null);
        }
        if (intent == UserIntent.FILE_GENERATE) {
            return ReplyResult.documents(intent, fileGenerationTool.generate(userId, userText), null);
        }
        //明确记忆命令走同步管理入口，必须根据真实写库结果生成回复。
        if (intent == UserIntent.MEMORY_MANAGE) {
            return ReplyResult.text(intent, aiChatService.manageMemory(userId, userText).reply(), null);
        }
        if (intent == UserIntent.VOICE_REPLY) {
            return ReplyResult.audio(intent, aiChatService.chatForVoiceReply(userId, userText).reply(), null);
        }
        return ReplyResult.text(intent, aiChatService.chat(userId, userText).reply(), null);
    }

    private String resolveBrowserRequest(String userId, String userText) {
        String request = userText == null ? "" : userText.trim();
        if (HTTP_URL_PATTERN.matcher(request).find()) {
            return request;
        }
        List<Message> messages = chatMemory.get(userId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            java.util.regex.Matcher matcher = HTTP_URL_PATTERN.matcher(messages.get(index).getText());
            String latestUrl = null;
            while (matcher.find()) {
                latestUrl = matcher.group();
            }
            if (latestUrl != null) {
                return latestUrl + System.lineSeparator() + request;
            }
        }
        return request;
    }

    private void saveSpecialFlowUserMessage(String userId, String userText, UserIntent intent) {
        if (intent != UserIntent.IMAGE_GENERATE
                && intent != UserIntent.IMAGE_EDIT
                && intent != UserIntent.IMAGE_UNDERSTAND
                && intent != UserIntent.FILE_GENERATE
                && intent != UserIntent.BROWSER_TASK) {
            return;
        }
        String normalizedUserText = userText.trim();

        sqliteChatMessageRepository.save(
                userId,
                PersistedChatMessage.Role.USER,
                normalizedUserText
        );

        if (intent == UserIntent.BROWSER_TASK) {
            chatMemory.add(userId, List.of(new UserMessage(normalizedUserText)));
        }
    }
    /** 将当前待处理图片写入聊天记忆。 */
    public void saveReceivedImageMemory(String userId) {
        PendingUserImage image = imageContextService.findActive(userId)
                .orElseThrow(() -> new IllegalStateException("当前图片上下文不存在"));
        saveImageMemory(userId, image, "用户此前发送了一张图片");
    }

    /** 识别生成图片；失败不影响图片发送。 */
    private void saveGeneratedImageMemoryAsync(String userId, GeneratedImage generatedImage, String imageSource) {
        try {
            CompletableFuture.runAsync(() -> saveGeneratedImageMemoryQuietly(userId, generatedImage, imageSource),
                    imageMemoryExecutor);
        } catch (RejectedExecutionException exception) {
            log.warn("[iLink][GENERATED_IMAGE_MEMORY_REJECTED] userId={}", userId, exception);
        }
    }

    private void saveGeneratedImageMemoryQuietly(String userId, GeneratedImage generatedImage, String imageSource) {
        try {
            saveImageMemory(userId, new PendingUserImage(generatedImage.bytes(), generatedImage.contentType(), Instant.now()),
                    imageSource);
        } catch (RuntimeException exception) {
            log.error("[iLink][GENERATED_IMAGE_MEMORY_SAVE_FAILED] userId={}", userId, exception);
        }
    }

    private void saveImageMemory(String userId, PendingUserImage image, String imageSource) {
        log.info("[AI][IMAGE_MEMORY_UNDERSTAND][START] userId={}", userId);
        String summary = imageUnderstandingService.understand(image, IMAGE_MEMORY_PROMPT);
        String imageMemoryText = """
                【图片识别记忆】
                %s，后台识别结果如下：
                %s
                """.formatted(imageSource, summary);
        chatMemory.add(userId, List.of(new AssistantMessage(imageMemoryText)));
        sqliteChatMessageRepository.save(userId, PersistedChatMessage.Role.ASSISTANT, imageMemoryText);
        sqliteChatMessageRepository.softDeleteOldMessages(userId, 100);
        log.info("[AI][IMAGE_MEMORY_UNDERSTAND][SUCCESS] userId={}, summaryLength={}", userId, summary.length());
    }

    /** 回复结果类型。 */
    public enum ReplyResultType { TEXT, IMAGE, DOCUMENT, AUDIO }

    /** 承载不同业务链路产生的待发送内容。 */
    public record ReplyResult(ReplyResultType type, UserIntent intent, String answer, GeneratedImage image,
            List<GeneratedDocument> documents, PendingUserImage imageToClear) {
        /** 创建文字结果。 */
        public static ReplyResult text(UserIntent intent, String answer, PendingUserImage imageToClear) {
            return new ReplyResult(ReplyResultType.TEXT, intent, answer, null, null, imageToClear);
        }
        /** 创建图片结果。 */
        public static ReplyResult image(UserIntent intent, GeneratedImage image, PendingUserImage imageToClear) {
            return new ReplyResult(ReplyResultType.IMAGE, intent, null, image, null, imageToClear);
        }
        /** 创建文件结果。 */
        public static ReplyResult documents(UserIntent intent, List<GeneratedDocument> documents,
                PendingUserImage imageToClear) {
            return new ReplyResult(ReplyResultType.DOCUMENT, intent, null, null, documents, imageToClear);
        }
        /** 创建需要语音合成的文字结果。 */
        public static ReplyResult audio(UserIntent intent, String answer, PendingUserImage imageToClear) {
            return new ReplyResult(ReplyResultType.AUDIO, intent, answer, null, null, imageToClear);
        }
    }
}
