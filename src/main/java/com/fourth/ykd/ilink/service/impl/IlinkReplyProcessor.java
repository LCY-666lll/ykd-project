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
    private final ReminderService reminderService;

    /** 注入现有的回复处理依赖。 */
    public IlinkReplyProcessor(AiChatService aiChatService, DeepSeekIntentRouter intentRouter,
            ImageGenerationService imageGenerationService, ImageReferenceGenerationService imageReferenceGenerationService,
            ImageUnderstandingService imageUnderstandingService, ImageContextService imageContextService,
            FileGenerationTool fileGenerationTool, ChatMemory chatMemory, ReminderService reminderService) {
        this.aiChatService = aiChatService; this.intentRouter = intentRouter;
        this.imageGenerationService = imageGenerationService;
        this.imageReferenceGenerationService = imageReferenceGenerationService;
        this.imageUnderstandingService = imageUnderstandingService; this.imageContextService = imageContextService;
        this.fileGenerationTool = fileGenerationTool; this.chatMemory = chatMemory;
        this.reminderService = reminderService;
    }

    /** 按现有意图执行业务，并产出待发送结果。contextToken 从用户消息提取，用于提醒推送。 */
    public ReplyResult process(String userId, String userText, String contextToken, boolean voiceMode) {
        Optional<PendingUserImage> pendingImage = imageContextService.findActive(userId);
        UserIntent intent = intentRouter.route(userId, userText, pendingImage.isPresent());
        if (pendingImage.isEmpty() && (intent == UserIntent.IMAGE_EDIT || intent == UserIntent.IMAGE_UNDERSTAND)) {
            log.warn("[iLink][IMAGE_CONTEXT_MISSING] userId={}, intent={}", userId, intent);
            intent = UserIntent.TEXT;
        }
        log.info("[iLink][{}] userId={}, intent={}, hasPendingImage={}",
                voiceMode ? "VOICE_ROUTED" : "ROUTED", userId, intent, pendingImage.isPresent());
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_UNDERSTAND) {
            log.info("[AI][IMAGE_UNDERSTAND][START] userId={}", userId);
            String answer = imageUnderstandingService.understand(pendingImage.get(), userText);
            log.info("[AI][IMAGE_UNDERSTAND][SUCCESS] userId={}, answerLength={}", userId, answer.length());
            return ReplyResult.text(intent, answer, null);
        }
        if (pendingImage.isPresent() && intent == UserIntent.IMAGE_EDIT) {
            log.info("[AI][IMAGE_EDIT][START] userId={}", userId);
            GeneratedImage image = imageReferenceGenerationService.generate(pendingImage.get(), userText);
            saveGeneratedImageMemoryQuietly(userId, image, "机器人此前根据用户要求编辑并生成了一张图片");
            log.info("[AI][IMAGE_EDIT][SUCCESS] userId={}, imageBytes={}", userId, image.bytes().length);
            return ReplyResult.image(intent, image, pendingImage.get());
        }
        if (intent == UserIntent.IMAGE_GENERATE) {
            log.info("[AI][IMAGE_GENERATE][START] userId={}", userId);
            String imagePrompt = resolveImagePrompt(userId, userText);
            GeneratedImage image = imageGenerationService.generate(imagePrompt);
            saveGeneratedImageMemoryQuietly(userId, image, "机器人此前根据用户请求生成了一张图片");
            log.info("[AI][IMAGE_GENERATE][SUCCESS] userId={}, imageBytes={}", userId, image.bytes().length);
            return ReplyResult.image(intent, image, null);
        }
        if (intent == UserIntent.FILE_GENERATE) {
            return ReplyResult.documents(intent, fileGenerationTool.generate(userId, userText),
                    pendingImage.orElse(null));
        }
        if (intent == UserIntent.CREATE_TASK) {
            log.info("[REMINDER][CREATE_TASK] userId={}, hasContextToken={}", userId, contextToken != null);
            String answer = reminderService.createReminder(userId, contextToken, userText);
            return ReplyResult.text(intent, answer, pendingImage.orElse(null));
        }
        if (intent == UserIntent.DELETE_TASK) {
            log.info("[REMINDER][DELETE_TASK] userId={}", userId);
            String answer = reminderService.deleteReminder(userId);
            return ReplyResult.text(intent, answer, pendingImage.orElse(null));
        }
        if (intent == UserIntent.VOICE_REPLY) {
            return ReplyResult.audio(intent, aiChatService.chat(userId, userText).reply(),
                    pendingImage.orElse(null));
        }
        return ReplyResult.text(intent, aiChatService.chat(userId, userText).reply(),
                pendingImage.orElse(null));
    }

    private String resolveImagePrompt(String userId, String userText) {
        if (!(userText.contains("上面") || userText.contains("上述") || userText.contains("刚才")
                || userText.contains("前面") || userText.contains("这份计划") || userText.contains("这个计划"))) {
            return userText;
        }
        String prompt = aiChatService.chat(userId, "根据当前会话中用户刚刚确认的内容，将本次图片请求改写为完整、具体的中文图片生成提示词。必须保留活动主题、餐厅名称、核心规则、视觉主体和用户强调的重点；只输出图片提示词，不要解释，不要 Markdown。").reply();
        return prompt == null || prompt.isBlank() ? userText : prompt.trim();
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
        log.info("[AI][IMAGE_MEMORY_UNDERSTAND][START] userId={}", userId);
        String summary = imageUnderstandingService.understand(image, IMAGE_MEMORY_PROMPT);
        chatMemory.add(userId, List.of(new AssistantMessage("""
                【图片识别记忆】
                %s，后台识别结果如下：
                %s
                """.formatted(imageSource, summary))));
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
