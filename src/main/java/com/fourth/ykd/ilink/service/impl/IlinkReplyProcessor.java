package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ai.dto.*;
import com.fourth.ykd.ai.routing.*;
import com.fourth.ykd.ai.service.*;
import com.fourth.ykd.ai.utils.FileGenerationTool;
import java.time.Instant;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
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
    private final AiChatService aiChatService;
    private final DeepSeekIntentRouter intentRouter;
    private final ImageGenerationService imageGenerationService;
    private final ImageReferenceGenerationService imageReferenceGenerationService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ImageContextService imageContextService;
    private final FileGenerationTool fileGenerationTool;
    private final ChatMemory chatMemory;

    /** 注入现有的回复处理依赖。 */
    public IlinkReplyProcessor(AiChatService aiChatService, DeepSeekIntentRouter intentRouter,
            ImageGenerationService imageGenerationService, ImageReferenceGenerationService imageReferenceGenerationService,
            ImageUnderstandingService imageUnderstandingService, ImageContextService imageContextService,
            FileGenerationTool fileGenerationTool, ChatMemory chatMemory) {
        this.aiChatService = aiChatService; this.intentRouter = intentRouter;
        this.imageGenerationService = imageGenerationService;
        this.imageReferenceGenerationService = imageReferenceGenerationService;
        this.imageUnderstandingService = imageUnderstandingService; this.imageContextService = imageContextService;
        this.fileGenerationTool = fileGenerationTool; this.chatMemory = chatMemory;
    }

    /** 按现有意图执行业务，并产出待发送结果。 */
    public ReplyResult process(String userId, String userText, boolean voiceMode) {
        Optional<PendingUserImage> pendingImage = imageContextService.findActive(userId);
        UserIntent intent = intentRouter.route(userText, pendingImage.isPresent());
        log.info("[iLink][{}] userId={}, intent={}, hasPendingImage={}",
                voiceMode ? "VOICE_ROUTED" : "ROUTED", userId, intent, pendingImage.isPresent());
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_UNDERSTAND) {
            return ReplyResult.text(intent, imageUnderstandingService.understand(pendingImage.get(), userText),
                    pendingImage.get(), "IMAGE_UNDERSTAND");
        }
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_EDIT) {
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
            return ReplyResult.documents(intent, fileGenerationTool.generate(userId, userText),
                    pendingImage.orElse(null), "FILE_GENERATE");
        }
        return ReplyResult.text(intent, aiChatService.chat(userId, userText).reply(),
                pendingImage.orElse(null), voiceMode ? "VOICE_TEXT" : "TEXT");
    }

    /** 将当前待处理图片写入聊天记忆。 */
    public void saveReceivedImageMemory(String userId) {
        PendingUserImage image = imageContextService.findActive(userId)
                .orElseThrow(() -> new IllegalStateException("当前图片上下文不存在"));
        saveImageMemory(userId, image, "用户此前发送了一张图片");
    }

    /** 识别生成图片；失败不影响图片发送。 */
    private void saveGeneratedImageMemoryQuietly(String userId, GeneratedImage generatedImage, String imageSource) {
        try {
            saveImageMemory(userId, new PendingUserImage(generatedImage.bytes(), generatedImage.contentType(), Instant.now()),
                    imageSource);
        } catch (RuntimeException exception) {
            log.error("[iLink][GENERATED_IMAGE_MEMORY_SAVE_FAILED] userId={}", userId, exception);
        }
    }

    /** 识图并写入同一用户会话记忆。 */
    private void saveImageMemory(String userId, PendingUserImage image, String imageSource) {
        String summary = imageUnderstandingService.understand(image, IMAGE_MEMORY_PROMPT);
        chatMemory.add(userId, List.of(new AssistantMessage("""
                【图片识别记忆】
                %s，后台识别结果如下：
                %s
                """.formatted(imageSource, summary))));
    }

    /** 回复结果类型。 */
    public enum ReplyResultType { TEXT, IMAGE, DOCUMENT }

    /** 承载不同业务链路产生的待发送内容。 */
    public record ReplyResult(ReplyResultType type, UserIntent intent, String answer, GeneratedImage image,
            List<GeneratedDocument> documents, PendingUserImage imageToClear, String clearReason) {
        /** 创建文字结果。 */
        public static ReplyResult text(UserIntent intent, String answer, PendingUserImage imageToClear, String reason) {
            return new ReplyResult(ReplyResultType.TEXT, intent, answer, null, null, imageToClear, reason);
        }
        /** 创建图片结果。 */
        public static ReplyResult image(UserIntent intent, GeneratedImage image, PendingUserImage imageToClear, String reason) {
            return new ReplyResult(ReplyResultType.IMAGE, intent, null, image, null, imageToClear, reason);
        }
        /** 创建文件结果。 */
        public static ReplyResult documents(UserIntent intent, List<GeneratedDocument> documents,
                PendingUserImage imageToClear, String reason) {
            return new ReplyResult(ReplyResultType.DOCUMENT, intent, null, null, documents, imageToClear, reason);
        }
    }
}
