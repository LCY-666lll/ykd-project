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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;
/**
 * 微信回复业务处理器。
 *
 * 主要职责：
 * 1. 查询用户当前是否存在图片上下文；
 * 2. 调用意图路由器判断业务类型；
 * 3. 对图片意图进行代码层前置条件校验；
 * 4. 根据意图调用聊天、图片、文件、浏览器等业务服务；
 * 5. 将不同业务结果统一包装成 ReplyResult；
 * 6. 为图片和浏览器等特殊流程补充聊天记录与图片记忆。
 *
 * 本类不负责：
 * 1. 消息排队和同用户串行；
 * 2. 直接向微信发送消息；
 * 3. 底层模型或浏览器工具的具体实现。
 */
@Slf4j
@Service
public class IlinkReplyProcessor {

    /**
     * 后台识别图片并生成聊天记忆时使用的固定提示词。
     * 目标：
     * 将图片转换成可靠、简洁的中文文字摘要，
     * 供后续多轮文本聊天继续引用。
     */
    private static final String IMAGE_MEMORY_PROMPT = """
            请识别这张图片，并生成供后续多轮聊天使用的中文图片记忆。
            只描述图片中确实可见的内容；不确定时明确说明无法确认；不要寒暄、提问或编造。
            """;

    /** 用于从当前消息或历史消息中提取 HTTP/HTTPS URL。 */
    private static final Pattern HTTP_URL_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?i)https?://\\S+"
            );

    /** 普通聊天、语音回答和图片 Prompt 整理等 AI 核心服务。 */
    private final AiChatService aiChatService;

    /** 根据用户文字和图片上下文判断业务意图。 */
    private final DeepSeekIntentRouter intentRouter;

    /** 根据文字 Prompt 生成新图片。 */
    private final ImageGenerationService imageGenerationService;

    /** 根据原图和修改要求生成编辑后的图片。 */
    private final ImageReferenceGenerationService
            imageReferenceGenerationService;

    /** 负责图片理解以及后台图片记忆识别。 */
    private final ImageUnderstandingService
            imageUnderstandingService;

    /** 保存和查询用户当前可继续处理的图片上下文。 */
    private final ImageContextService imageContextService;

    /** 生成 PDF、DOCX、XLSX 等文件。 */
    private final FileGenerationTool fileGenerationTool;

    /** 保存当前用户的短期聊天上下文。 */
    private final ChatMemory chatMemory;

    /** 将完整聊天记录持久化到 SQLite。 */
    private final SqliteChatMessageRepository
            sqliteChatMessageRepository;

    /** 后台生成图片文字记忆，避免阻塞图片发送。 */
    private final Executor imageMemoryExecutor;

    /** 执行基于 Playwright MCP 的公开网页任务。 */
    private final BrowserTaskService browserTaskService;

    /**
     * 注入回复处理所需要的全部业务组件。
     */
    public IlinkReplyProcessor(
            AiChatService aiChatService,
            DeepSeekIntentRouter intentRouter,
            ImageGenerationService imageGenerationService,
            ImageReferenceGenerationService
                    imageReferenceGenerationService,
            ImageUnderstandingService imageUnderstandingService,
            ImageContextService imageContextService,
            FileGenerationTool fileGenerationTool,
            ChatMemory chatMemory,
            SqliteChatMessageRepository
                    sqliteChatMessageRepository,
            @Qualifier("memoryExecutor")
            Executor imageMemoryExecutor,
            BrowserTaskService browserTaskService
    ) {
        this.aiChatService = aiChatService;
        this.intentRouter = intentRouter;
        this.imageGenerationService =
                imageGenerationService;
        this.imageReferenceGenerationService =
                imageReferenceGenerationService;
        this.imageUnderstandingService =
                imageUnderstandingService;
        this.imageContextService = imageContextService;
        this.fileGenerationTool = fileGenerationTool;
        this.chatMemory = chatMemory;
        this.sqliteChatMessageRepository =
                sqliteChatMessageRepository;
        this.imageMemoryExecutor = imageMemoryExecutor;
        this.browserTaskService = browserTaskService;
    }

    /**
     * 根据用户输入执行具体业务，并返回统一的待发送结果。
     * @param userId 当前用户 ID
     * @param userText 用户输入文字
     * @param voiceMode 当前是否处于语音回复链路
     * @return 统一回复结果
     * 核心流程：
     * 1. 查询当前图片上下文；
     * 2. 路由用户意图；
     * 3. 校验图片意图的前置条件；
     * 4. 为特殊业务保存用户消息；
     * 5. 根据意图调用对应业务服务；
     * 6. 将结果包装成 ReplyResult。
     */
    public ReplyResult process(
            String userId,
            String userText,
            boolean voiceMode
    ) {
        // 路由前先查询图片上下文，因为图片编辑和理解依赖它。
        Optional<PendingUserImage> pendingImage =
                imageContextService.findActive(userId);

        // 将“当前是否有图片”一起交给意图路由器。
        UserIntent intent =
                intentRouter.route(
                        userId,
                        userText,
                        pendingImage.isPresent()
                );

        /*
         * 模型可能错误路由为图片编辑或理解。
         * 没有图片时进行代码层兜底，回退普通文字聊天。
         */
        if (pendingImage.isEmpty()
                && (intent == UserIntent.IMAGE_EDIT
                || intent == UserIntent.IMAGE_UNDERSTAND)) {

            log.warn(
                    "[iLink][IMAGE_CONTEXT_MISSING] "
                            + "userId={}, intent={}",
                    userId,
                    intent
            );

            intent = UserIntent.TEXT;
        }

        // voiceMode 当前主要用于区分普通路由和语音链路日志。
        log.info(
                "[iLink][{}] userId={}, "
                        + "intent={}, hasPendingImage={}",
                voiceMode
                        ? "VOICE_ROUTED"
                        : "ROUTED",
                userId,
                intent,
                pendingImage.isPresent()
        );

        /*
         * 图片、文件和浏览器等业务绕过普通聊天链路，
         * 因此需要手动补充用户消息持久化。
         */
        saveSpecialFlowUserMessage(
                userId,
                userText,
                intent
        );

        // 图片理解：使用当前图片回答用户问题。
        if (pendingImage.isPresent()
                && intent
                == UserIntent.IMAGE_UNDERSTAND) {

            log.info(
                    "[AI][IMAGE_UNDERSTAND][START] "
                            + "userId={}",
                    userId
            );

            String answer =
                    imageUnderstandingService.understand(
                            pendingImage.get(),
                            userText
                    );

            log.info(
                    "[AI][IMAGE_UNDERSTAND][SUCCESS] "
                            + "userId={}, answerLength={}",
                    userId,
                    answer.length()
            );

            return ReplyResult.text(
                    intent,
                    answer,
                    null
            );
        }

        // 图片编辑：使用原图和修改要求生成新图片。
        if (pendingImage.isPresent()
                && intent == UserIntent.IMAGE_EDIT) {

            log.info(
                    "[AI][IMAGE_EDIT][START] userId={}",
                    userId
            );

            GeneratedImage image =
                    imageReferenceGenerationService.generate(
                            pendingImage.get(),
                            userText
                    );

            /*
             * 新图片生成成功后，后台识别并生成文字记忆。
             * 记忆失败不会影响当前图片返回。
             */
            saveGeneratedImageMemoryAsync(
                    userId,
                    image,
                    "机器人此前根据用户要求编辑并生成了一张图片"
            );

            log.info(
                    "[AI][IMAGE_EDIT][SUCCESS] "
                            + "userId={}, imageBytes={}",
                    userId,
                    image.bytes().length
            );

            /*
             * 将原图片作为 imageToClear 交给下游，
             * 具体清理时机由发送层决定。
             */
            return ReplyResult.image(
                    intent,
                    image,
                    pendingImage.get()
            );
        }

        // 文生图：先整理图片 Prompt，再调用图片生成服务。
        if (intent == UserIntent.IMAGE_GENERATE) {
            log.info(
                    "[AI][IMAGE_GENERATE][START] "
                            + "userId={}",
                    userId
            );

            String imagePrompt =
                    aiChatService.prepareImagePrompt(
                            userId,
                            userText
                    );

            log.info(
                    "[AI][IMAGE_GENERATE][PROMPT] "
                            + "userId={}, promptLength={}",
                    userId,
                    imagePrompt.length()
            );

            GeneratedImage image =
                    imageGenerationService.generate(
                            imagePrompt
                    );

            saveGeneratedImageMemoryAsync(
                    userId,
                    image,
                    "机器人此前根据用户请求生成了一张图片"
            );

            log.info(
                    "[AI][IMAGE_GENERATE][SUCCESS] "
                            + "userId={}, imageBytes={}",
                    userId,
                    image.bytes().length
            );

            return ReplyResult.image(
                    intent,
                    image,
                    null
            );
        }

        // 浏览器任务：补全 URL 后交给独立浏览器服务执行。
        if (intent == UserIntent.BROWSER_TASK) {
            log.info(
                    "[AI][BROWSER_TASK][START] "
                            + "userId={}",
                    userId
            );

            String browserRequest =
                    resolveBrowserRequest(
                            userId,
                            userText
                    );

            String answer =
                    browserTaskService.execute(
                            userId,
                            browserRequest
                    );

            // 浏览器链路绕过普通聊天，需要手动保存助手结果。
            chatMemory.add(
                    userId,
                    List.of(
                            new AssistantMessage(answer)
                    )
            );

            sqliteChatMessageRepository.save(
                    userId,
                    PersistedChatMessage.Role.ASSISTANT,
                    answer
            );

            sqliteChatMessageRepository
                    .softDeleteOldMessages(
                            userId,
                            100
                    );

            log.info(
                    "[AI][BROWSER_TASK][FINISHED] "
                            + "userId={}, answerLength={}",
                    userId,
                    answer.length()
            );

            return ReplyResult.text(
                    intent,
                    answer,
                    null
            );
        }

        // 文件生成：生成一个或多个文件并交给发送层。
        if (intent == UserIntent.FILE_GENERATE) {
            return ReplyResult.documents(
                    intent,
                    fileGenerationTool.generate(
                            userId,
                            userText
                    ),
                    null
            );
        }

        /*
         * 明确记忆管理由上一层放入独立线程池。
         * 当前只返回即时处理中提示。
         */
        if (intent == UserIntent.MEMORY_MANAGE) {
            return ReplyResult.text(
                    intent,
                    "收到，正在处理你的长期记忆请求，完成后通知你。",
                    null
            );
        }

        /*
         * 明确语音回复：
         * 当前返回的是适合 TTS 的文字，发送层再进行语音合成。
         */
        if (intent == UserIntent.VOICE_REPLY) {
            return ReplyResult.audio(
                    intent,
                    aiChatService
                            .chatForVoiceReply(
                                    userId,
                                    userText
                            )
                            .reply(),
                    null
            );
        }

        // 其余意图统一回到普通聊天链路。
        return ReplyResult.text(
                intent,
                aiChatService
                        .chat(
                                userId,
                                userText
                        )
                        .reply(),
                null
        );
    }

    /**
     * 补全浏览器任务请求中的 URL。
     *
     * @param userId 用户 ID
     * @param userText 当前用户请求
     * @return 包含 URL 的浏览器请求
     *
     * 处理规则：
     * 1. 当前消息有 URL，直接返回；
     * 2. 当前消息没有 URL，从最近聊天记录向前查找；
     * 3. 找到最近 URL 后与当前请求拼接；
     * 4. 找不到时返回原请求。
     */
    private String resolveBrowserRequest(
            String userId,
            String userText
    ) {
        String request =
                userText == null
                        ? ""
                        : userText.trim();

        // 当前请求已经包含 URL，不需要查历史。
        if (HTTP_URL_PATTERN
                .matcher(request)
                .find()) {
            return request;
        }

        List<Message> messages =
                chatMemory.get(userId);

        // 从最新消息向最旧消息查找最近 URL。
        for (int index = messages.size() - 1;
             index >= 0;
             index--) {

            Matcher matcher =
                    HTTP_URL_PATTERN.matcher(
                            messages
                                    .get(index)
                                    .getText()
                    );

            String latestUrl = null;

            // 同一条消息存在多个 URL 时，保留最后一个。
            while (matcher.find()) {
                latestUrl = matcher.group();
            }

            if (latestUrl != null) {
                return latestUrl
                        + System.lineSeparator()
                        + request;
            }
        }

        return request;
    }

    /**
     * 为绕过普通聊天链路的特殊业务保存用户消息。
     * @param userId 用户 ID
     * @param userText 用户原始文字
     * @param intent 当前业务意图
     */
    private void saveSpecialFlowUserMessage(
            String userId,
            String userText,
            UserIntent intent
    ) {
        // 普通聊天和其他意图由自身链路处理消息记录。
        if (intent != UserIntent.IMAGE_GENERATE
                && intent != UserIntent.IMAGE_EDIT
                && intent
                != UserIntent.IMAGE_UNDERSTAND
                && intent != UserIntent.FILE_GENERATE
                && intent != UserIntent.BROWSER_TASK) {
            return;
        }

        String normalizedUserText =
                userText.trim();

        // 特殊业务统一将用户请求持久化到 SQLite。
        sqliteChatMessageRepository.save(
                userId,
                PersistedChatMessage.Role.USER,
                normalizedUserText
        );

        /*
         * 浏览器任务需要在后续追问中回查 URL，
         * 因此额外写入短期 ChatMemory。
         */
        if (intent == UserIntent.BROWSER_TASK) {
            chatMemory.add(
                    userId,
                    List.of(
                            new UserMessage(
                                    normalizedUserText
                            )
                    )
            );
        }
    }

    /**
     * 将当前用户刚刚上传的图片转换成文字记忆。
     *
     * @param userId 用户 ID
     */
    public void saveReceivedImageMemory(
            String userId
    ) {
        PendingUserImage image =
                imageContextService
                        .findActive(userId)
                        .orElseThrow(
                                () -> new IllegalStateException(
                                        "当前图片上下文不存在"
                                )
                        );

        saveImageMemory(
                userId,
                image,
                "用户此前发送了一张图片"
        );
    }

    /**
     * 异步为生成或编辑后的图片创建文字记忆。
     *
     * @param userId 用户 ID
     * @param generatedImage 新生成的图片
     * @param imageSource 图片来源说明
     *
     * 记忆生成属于附加能力，
     * 线程池拒绝时只记录日志，不影响图片发送。
     */
    private void saveGeneratedImageMemoryAsync(
            String userId,
            GeneratedImage generatedImage,
            String imageSource
    ) {
        try {
            CompletableFuture.runAsync(
                    () -> saveGeneratedImageMemoryQuietly(
                            userId,
                            generatedImage,
                            imageSource
                    ),
                    imageMemoryExecutor
            );
        } catch (RejectedExecutionException exception) {
            log.warn(
                    "[iLink][GENERATED_IMAGE_MEMORY_REJECTED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /**
     * 安静地保存生成图片的文字记忆。
     *
     * 失败只记录日志，不再向外抛出，
     * 避免影响已经成功生成的图片。
     */
    private void saveGeneratedImageMemoryQuietly(
            String userId,
            GeneratedImage generatedImage,
            String imageSource
    ) {
        try {
            PendingUserImage image =
                    new PendingUserImage(
                            generatedImage.bytes(),
                            generatedImage.contentType(),
                            Instant.now()
                    );

            saveImageMemory(
                    userId,
                    image,
                    imageSource
            );
        } catch (RuntimeException exception) {
            log.error(
                    "[iLink][GENERATED_IMAGE_MEMORY_SAVE_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /**
     * 调用视觉模型识别图片，并写入短期和持久化聊天记录。
     *
     * @param userId 用户 ID
     * @param image 待识别图片
     * @param imageSource 图片来源说明
     */
    private void saveImageMemory(
            String userId,
            PendingUserImage image,
            String imageSource
    ) {
        log.info(
                "[AI][IMAGE_MEMORY_UNDERSTAND][START] "
                        + "userId={}",
                userId
        );

        String summary =
                imageUnderstandingService.understand(
                        image,
                        IMAGE_MEMORY_PROMPT
                );

        String imageMemoryText = """
                【图片识别记忆】
                %s，后台识别结果如下：
                %s
                """.formatted(
                imageSource,
                summary
        );

        // 写入短期聊天上下文。
        chatMemory.add(
                userId,
                List.of(
                        new AssistantMessage(
                                imageMemoryText
                        )
                )
        );

        // 写入 SQLite 持久化聊天记录。
        sqliteChatMessageRepository.save(
                userId,
                PersistedChatMessage.Role.ASSISTANT,
                imageMemoryText
        );

        // 控制单个用户活跃聊天记录数量。
        sqliteChatMessageRepository
                .softDeleteOldMessages(
                        userId,
                        100
                );

        log.info(
                "[AI][IMAGE_MEMORY_UNDERSTAND][SUCCESS] "
                        + "userId={}, summaryLength={}",
                userId,
                summary.length()
        );
    }

    /** 微信回复结果的内容类型。 */
    public enum ReplyResultType {
        TEXT,
        IMAGE,
        DOCUMENT,
        AUDIO
    }

    /**
     * 统一承载不同业务链路产生的待发送结果。
     *
     * @param type 回复内容类型
     * @param intent 本轮用户意图
     * @param answer 文字答案或待语音合成文字
     * @param image 待发送图片
     * @param documents 待发送文件列表
     * @param imageToClear 下游发送成功后可清理的旧图片
     */
    public record ReplyResult(
            ReplyResultType type,
            UserIntent intent,
            String answer,
            GeneratedImage image,
            List<GeneratedDocument> documents,
            PendingUserImage imageToClear
    ) {

        /** 创建文字回复结果。 */
        public static ReplyResult text(
                UserIntent intent,
                String answer,
                PendingUserImage imageToClear
        ) {
            return new ReplyResult(
                    ReplyResultType.TEXT,
                    intent,
                    answer,
                    null,
                    null,
                    imageToClear
            );
        }

        /** 创建图片回复结果。 */
        public static ReplyResult image(
                UserIntent intent,
                GeneratedImage image,
                PendingUserImage imageToClear
        ) {
            return new ReplyResult(
                    ReplyResultType.IMAGE,
                    intent,
                    null,
                    image,
                    null,
                    imageToClear
            );
        }

        /** 创建文件回复结果。 */
        public static ReplyResult documents(
                UserIntent intent,
                List<GeneratedDocument> documents,
                PendingUserImage imageToClear
        ) {
            return new ReplyResult(
                    ReplyResultType.DOCUMENT,
                    intent,
                    null,
                    null,
                    documents,
                    imageToClear
            );
        }

        /** 创建需要后续语音合成的文字结果。 */
        public static ReplyResult audio(
                UserIntent intent,
                String answer,
                PendingUserImage imageToClear
        ) {
            return new ReplyResult(
                    ReplyResultType.AUDIO,
                    intent,
                    answer,
                    null,
                    null,
                    imageToClear
            );
        }
    }
}