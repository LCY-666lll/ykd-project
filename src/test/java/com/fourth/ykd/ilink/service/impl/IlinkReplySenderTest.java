package com.fourth.ykd.ilink.service.impl;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fourth.ykd.ai.dto.GeneratedAudio;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.AudioSynthesisService;
import com.fourth.ykd.ai.service.ImageContextService;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 验证语音输入不会默认转换为语音回复。 */
class IlinkReplySenderTest {

    private static final String USER_ID = "wechat-user";
    private static final String VOICE_CAPABILITY_TIP = "如果您想与我语音交流，我也可以回复您语音哦。";

    private final AudioSynthesisService audioSynthesisService = mock(AudioSynthesisService.class);
    private final ImageContextService imageContextService = mock(ImageContextService.class);
    private final ILinkClient client = mock(ILinkClient.class);
    private final IlinkReplySender sender = new IlinkReplySender(audioSynthesisService, imageContextService);

    @Test
    void shouldSendTextAndCapabilityTipForNormalVoiceInput() throws Exception {
        IlinkReplyProcessor.ReplyResult result = IlinkReplyProcessor.ReplyResult.text(
                UserIntent.TEXT, "今天的新闻内容", null);

        sender.sendVoiceModeReply(client, USER_ID, result, System.currentTimeMillis());

        InOrder order = inOrder(client);
        order.verify(client).sendText(USER_ID, "今天的新闻内容");
        order.verify(client).sendText(USER_ID, VOICE_CAPABILITY_TIP);
        verifyNoInteractions(audioSynthesisService);
    }

    @Test
    void shouldNotAppendCapabilityTipForImageUnderstandingResult() throws Exception {
        IlinkReplyProcessor.ReplyResult result = IlinkReplyProcessor.ReplyResult.text(
                UserIntent.IMAGE_UNDERSTAND, "图片中有一只猫", null);

        sender.sendVoiceModeReply(client, USER_ID, result, System.currentTimeMillis());

        verify(client).sendText(USER_ID, "图片中有一只猫");
        verify(client, never()).sendText(USER_ID, VOICE_CAPABILITY_TIP);
        verifyNoInteractions(audioSynthesisService);
    }

    @Test
    void shouldSynthesizeAudioOnlyForExplicitVoiceReply() throws Exception {
        byte[] audioBytes = {1, 2, 3};
        when(audioSynthesisService.synthesize("语音回答内容"))
                .thenReturn(new GeneratedAudio(audioBytes, "reply.mp3", "audio/mpeg"));
        IlinkReplyProcessor.ReplyResult result = IlinkReplyProcessor.ReplyResult.audio(
                UserIntent.VOICE_REPLY, "语音回答内容", null);

        sender.sendVoiceModeReply(client, USER_ID, result, System.currentTimeMillis());

        verify(audioSynthesisService).synthesize("语音回答内容");
        verify(client).sendFile(USER_ID, audioBytes, "reply.mp3", null);
        verify(client, never()).sendText(USER_ID, VOICE_CAPABILITY_TIP);
    }
}