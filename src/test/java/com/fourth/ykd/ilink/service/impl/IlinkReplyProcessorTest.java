package com.fourth.ykd.ilink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.routing.DeepSeekIntentRouter;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ai.service.ImageContextService;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.ai.utils.FileGenerationTool;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

/** 验证图片上下文和文生图提示词分流。 */
class IlinkReplyProcessorTest {

    @Test
    void shouldKeepImageContextAfterImageUnderstanding() {
        ProcessorFixture fixture = new ProcessorFixture();
        PendingUserImage image = new PendingUserImage(new byte[]{1}, "image/png", Instant.now());
        when(fixture.imageContextService.findActive("user-1")).thenReturn(Optional.of(image));
        when(fixture.chatMemory.get("user-1")).thenReturn(List.of());
        when(fixture.intentRouter.route("user-1", "这张图里有什么", true))
                .thenReturn(UserIntent.IMAGE_UNDERSTAND);
        when(fixture.understandingService.understand(image, "这张图里有什么"))
                .thenReturn("图中有一只猫");

        IlinkReplyProcessor.ReplyResult result = fixture.processor.process(
                "user-1", "这张图里有什么", false);

        assertThat(result.intent()).isEqualTo(UserIntent.IMAGE_UNDERSTAND);
        assertThat(result.answer()).isEqualTo("图中有一只猫");
        assertThat(result.imageToClear()).isNull();
    }

    @Test
    void shouldPreparePromptForSimpleImageGeneration() {
        ProcessorFixture fixture = new ProcessorFixture();
        String userText = "生成一个火影忍者的图片";
        String preparedPrompt = "火影忍者主题画面";
        GeneratedImage image = new GeneratedImage(new byte[]{1}, "image.png", "image/png");
        when(fixture.imageContextService.findActive("user-1")).thenReturn(Optional.empty());
        when(fixture.intentRouter.route("user-1", userText, false)).thenReturn(UserIntent.IMAGE_GENERATE);
        when(fixture.aiChatService.prepareImagePrompt("user-1", userText)).thenReturn(preparedPrompt);
        when(fixture.imageGenerationService.generate(preparedPrompt)).thenReturn(image);

        IlinkReplyProcessor.ReplyResult result = fixture.processor.process("user-1", userText, true);

        assertThat(result.image()).isEqualTo(image);
        verify(fixture.aiChatService).prepareImagePrompt("user-1", userText);
        verify(fixture.imageGenerationService).generate(preparedPrompt);
    }

    @Test
    void shouldPreparePromptForRealtimeImageGeneration() {
        ProcessorFixture fixture = new ProcessorFixture();
        String userText = "根据今天杭州天气生成一张图片";
        String preparedPrompt = "杭州今日天气主题画面";
        GeneratedImage image = new GeneratedImage(new byte[]{1}, "image.png", "image/png");
        when(fixture.imageContextService.findActive("user-1")).thenReturn(Optional.empty());
        when(fixture.intentRouter.route("user-1", userText, false)).thenReturn(UserIntent.IMAGE_GENERATE);
        when(fixture.aiChatService.prepareImagePrompt("user-1", userText)).thenReturn(preparedPrompt);
        when(fixture.imageGenerationService.generate(preparedPrompt)).thenReturn(image);

        IlinkReplyProcessor.ReplyResult result = fixture.processor.process("user-1", userText, false);

        assertThat(result.image()).isEqualTo(image);
        verify(fixture.aiChatService).prepareImagePrompt("user-1", userText);
        verify(fixture.imageGenerationService).generate(preparedPrompt);
    }

    @Test
    void shouldKeepImageContextAfterNormalTextReply() {
        ProcessorFixture fixture = new ProcessorFixture();
        PendingUserImage image = new PendingUserImage(new byte[]{1}, "image/png", Instant.now());
        when(fixture.imageContextService.findActive("user-1")).thenReturn(Optional.of(image));
        when(fixture.intentRouter.route("user-1", "Tell me about Hangzhou", true)).thenReturn(UserIntent.TEXT);
        when(fixture.aiChatService.chat("user-1", "Tell me about Hangzhou"))
                .thenReturn(new AiChatResponse("Hangzhou introduction"));

        IlinkReplyProcessor.ReplyResult result = fixture.processor.process(
                "user-1", "Tell me about Hangzhou", false);

        assertThat(result.intent()).isEqualTo(UserIntent.TEXT);
        assertThat(result.imageToClear()).isNull();
    }

    private static final class ProcessorFixture {
        private final AiChatService aiChatService = mock(AiChatService.class);
        private final DeepSeekIntentRouter intentRouter = mock(DeepSeekIntentRouter.class);
        private final ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        private final ImageReferenceGenerationService referenceGenerationService =
                mock(ImageReferenceGenerationService.class);
        private final ImageUnderstandingService understandingService = mock(ImageUnderstandingService.class);
        private final ImageContextService imageContextService = mock(ImageContextService.class);
        private final ChatMemory chatMemory = mock(ChatMemory.class);
        private final IlinkReplyProcessor processor = new IlinkReplyProcessor(
                aiChatService,
                intentRouter,
                imageGenerationService,
                referenceGenerationService,
                understandingService,
                imageContextService,
                mock(FileGenerationTool.class),
                chatMemory,
                mock(SqliteChatMessageRepository.class),
                command -> { }
        );
    }
}