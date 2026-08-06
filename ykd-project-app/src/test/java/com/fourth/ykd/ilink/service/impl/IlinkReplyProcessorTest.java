package com.fourth.ykd.ilink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.routing.DeepSeekIntentRouter;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ai.service.ImageContextService;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.ai.utils.FileGenerationTool;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;

/** 验证识图完成后仍保留原图片，供下一轮图片编辑使用。 */
class IlinkReplyProcessorTest {

    @Test
    void shouldKeepImageContextAfterImageUnderstanding() {
        AiChatService aiChatService = mock(AiChatService.class);
        DeepSeekIntentRouter intentRouter = mock(DeepSeekIntentRouter.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ImageReferenceGenerationService referenceGenerationService = mock(ImageReferenceGenerationService.class);
        ImageUnderstandingService understandingService = mock(ImageUnderstandingService.class);
        ImageContextService imageContextService = mock(ImageContextService.class);
        FileGenerationTool fileGenerationTool = mock(FileGenerationTool.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        IlinkReplyProcessor processor = new IlinkReplyProcessor(aiChatService, intentRouter,
                imageGenerationService, referenceGenerationService, understandingService,
                imageContextService, fileGenerationTool, chatMemory);
        PendingUserImage image = new PendingUserImage(new byte[]{1}, "image/png", Instant.now());
        when(imageContextService.findActive("user-1")).thenReturn(Optional.of(image));
        when(intentRouter.route("user-1", "这张图里有什么", true)).thenReturn(UserIntent.IMAGE_UNDERSTAND);
        when(understandingService.understand(image, "这张图里有什么")).thenReturn("图中有一只猫");

        IlinkReplyProcessor.ReplyResult result = processor.process("user-1", "这张图里有什么", false);

        assertThat(result.intent()).isEqualTo(UserIntent.IMAGE_UNDERSTAND);
        assertThat(result.answer()).isEqualTo("图中有一只猫");
        assertThat(result.imageToClear()).isNull();
    }
}