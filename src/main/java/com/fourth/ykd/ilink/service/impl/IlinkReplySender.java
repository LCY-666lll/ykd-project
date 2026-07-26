package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ai.dto.*;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.*;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 负责文字、语音、图片和文件的实际发送。 */
@Slf4j
@Service
public class IlinkReplySender {
    private static final String VOICE_CAPABILITY_TIP = "如果您想与我语音交流，我也可以回复您语音哦。";

    private final AudioSynthesisService audioSynthesisService;
    private final ImageContextService imageContextService;

    /** 注入现有的发送依赖。 */
    public IlinkReplySender(AudioSynthesisService audioSynthesisService, ImageContextService imageContextService) {
        this.audioSynthesisService = audioSynthesisService; this.imageContextService = imageContextService;
    }

    /** 按文字消息方式发送结果。 */
    public void sendTextModeReply(ILinkClient client, String userId, IlinkReplyProcessor.ReplyResult result,
            long startedAt) throws IOException {
        if (result.type() == IlinkReplyProcessor.ReplyResultType.IMAGE) sendImageReply(client, userId, result, startedAt);
        else if (result.type() == IlinkReplyProcessor.ReplyResultType.DOCUMENT) sendDocumentReply(client, userId, result, startedAt);
        else if (result.type() == IlinkReplyProcessor.ReplyResultType.AUDIO) {
            sendAudioAnswer(client, userId, result.answer(), startedAt);
            clearImageContextIfNeeded(userId, result);
        }
        else {
            client.sendText(userId, result.answer());
            clearImageContextIfNeeded(userId, result);
            if (result.intent() == UserIntent.IMAGE_UNDERSTAND) {
                log.info("[iLink][IMAGE_UNDERSTOOD] toUserId={}, answer={}, elapsedMs={}",
                        userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
            } else {
                log.info("[iLink][REPLIED] toUserId={}, answer={}, elapsedMs={}",
                        userId, formatAnswerForLog(result.answer()), System.currentTimeMillis() - startedAt);
            }
        }
    }
    /** 按语音消息方式发送结果，普通回答默认使用文字。 */
    public void sendVoiceModeReply(ILinkClient client, String userId, IlinkReplyProcessor.ReplyResult result,
            long startedAt) throws IOException {
        if (result.type() == IlinkReplyProcessor.ReplyResultType.IMAGE) sendImageReply(client, userId, result, startedAt);
        else if (result.type() == IlinkReplyProcessor.ReplyResultType.DOCUMENT) sendDocumentReply(client, userId, result, startedAt);
        else if (result.type() == IlinkReplyProcessor.ReplyResultType.AUDIO) {
            sendAudioAnswer(client, userId, result.answer(), startedAt);
            clearImageContextIfNeeded(userId, result);
        }
        else {
            sendTextModeReply(client, userId, result, startedAt);
            if (result.intent() == UserIntent.TEXT) {
                sendTextQuietly(client, userId, VOICE_CAPABILITY_TIP);
            }
        }
    }
    /** 发送图片确认语。 */
    public void sendImageReceivedConfirmation(ILinkClient client, String userId) {
        try { client.sendTextWithTyping(userId, "已经看到您的图片啦，您想了解什么呢？", 800); }
        catch (IOException exception) { log.warn("[iLink][IMAGE_CONTEXT_REPLY_FAILED] userId={}", userId, exception); }
    }
    /** 启动输入状态。 */
    public void startTypingQuietly(ILinkClient client, String userId) {
        try { client.startTyping(userId); } catch (IOException exception) { log.warn("[iLink][TYPING_START_FAILED] userId={}", userId, exception); }
    }
    /** 停止输入状态。 */
    public void stopTypingQuietly(ILinkClient client, String userId) {
        try { client.stopTyping(userId); } catch (IOException exception) { log.warn("[iLink][TYPING_STOP_FAILED] userId={}", userId, exception); }
    }
    /** 发送普通失败提示。 */
    public void sendFailureMessage(ILinkClient client, String userId) { sendTextQuietly(client, userId, "抱歉，刚才处理失败了，请稍后再试。"); }
    /** 发送语音识别失败提示。 */
    public void sendVoiceRecognitionFailureMessage(ILinkClient client, String userId) { sendTextQuietly(client, userId, "这段语音暂时没有识别出文字，请重新发一遍或改用文字。"); }
    /** 发送语音处理失败提示。 */
    public void sendVoiceReplyFailureMessage(ILinkClient client, String userId) { sendTextQuietly(client, userId, "语音回复处理失败了，请稍后再试或改用文字。"); }

    /** 逐个发送生成文件。 */
    private void sendDocumentReply(ILinkClient client, String userId, IlinkReplyProcessor.ReplyResult result,
            long startedAt) throws IOException {
        for (GeneratedDocument document : result.documents()) {
            client.sendFile(userId, document.bytes(), document.fileName(), null);
            log.info("[iLink][REPLY_SENT] userId={}, type=FILE, fileName={}, fileBytes={}, elapsedMs={}",
                    userId, document.fileName(), document.bytes().length, System.currentTimeMillis() - startedAt);
        }
        clearImageContextIfNeeded(userId, result);
    }
    /** 发送图片结果。 */
    private void sendImageReply(ILinkClient client, String userId, IlinkReplyProcessor.ReplyResult result,
            long startedAt) throws IOException {
        GeneratedImage image = result.image(); client.sendImage(userId, image.bytes(), image.fileName(), null);
        log.info("[iLink][REPLY_SENT] userId={}, type=IMAGE, imageBytes={}, elapsedMs={}",
                userId, image.bytes().length, System.currentTimeMillis() - startedAt);
        clearImageContextIfNeeded(userId, result);
    }
    /** 合成语音，失败时退回文字。 */
    private void sendAudioAnswer(ILinkClient client, String userId, String answer, long startedAt) throws IOException {
        try {
            GeneratedAudio audio = audioSynthesisService.synthesize(answer);
            client.sendFile(userId, audio.bytes(), audio.fileName(), null);
            log.info("[iLink][REPLY_SENT] userId={}, type=AUDIO, fileName={}, fileBytes={}, elapsedMs={}",
                    userId, audio.fileName(), audio.bytes().length, System.currentTimeMillis() - startedAt);
        }
        catch (Exception exception) { log.warn("[iLink][VOICE_AUDIO_REPLY_FAILED] userId={}", userId, exception); client.sendText(userId, "语音回复生成失败了，我先用文字回复您：" + answer); }
    }
    /** 在本次图片操作完成后清理图片上下文。 */
    private void clearImageContextIfNeeded(String userId, IlinkReplyProcessor.ReplyResult result) {
        if (result.imageToClear() != null) imageContextService.remove(userId, result.imageToClear());
    }
    private String formatAnswerForLog(String answer) {
        if (answer == null) return "";
        String singleLine = answer.replaceAll("[\r\n]+", " ").trim();
        return singleLine.length() <= 1_000 ? singleLine : singleLine.substring(0, 1_000) + "...";
    }
    /** 安全发送固定文字。 */
    private void sendTextQuietly(ILinkClient client, String userId, String message) {
        try { client.sendText(userId, message); } catch (IOException exception) { log.warn("[iLink][TEXT_MESSAGE_SEND_FAILED] userId={}", userId, exception); }
    }
}
