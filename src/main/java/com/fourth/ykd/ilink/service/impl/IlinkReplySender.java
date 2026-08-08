package com.fourth.ykd.ilink.service.impl;
import com.fourth.ykd.ai.dto.*;
import com.fourth.ykd.ai.routing.UserIntent;
import com.fourth.ykd.ai.service.*;
import com.github.wechat.ilink.sdk.ILinkClient;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 微信 iLink 回复发送器。
 *
 * 核心职责：
 * 1. 根据 ReplyResult 类型选择发送文字、图片、文件或音频；
 * 2. 调用 ILinkClient 完成真实微信发送；
 * 3. 负责输入状态、排队提示和失败提示；
 * 4. 语音合成失败时降级为文字；
 * 5. 图片发送成功后维护图片上下文；
 * 6. 记录发送耗时和结果日志。
 */
@Slf4j
@Service
public class IlinkReplySender {

    /** 用户通过语音提问但本轮只返回普通文字时，补充的能力提示。 */
    private static final String VOICE_CAPABILITY_TIP =
            "如果您想与我语音交流，我也可以回复您语音哦。";

    /** 同一用户前一条消息仍在处理时发送的排队提示。 */
    private static final String QUEUE_WAITING_TIP =
            "上一条消息仍在处理中，已收到本条消息，完成后会按顺序继续处理。";

    /** 将文字答案合成为音频。 */
    private final AudioSynthesisService audioSynthesisService;

    /** 保存新图片上下文并清理旧图片上下文。 */
    private final ImageContextService imageContextService;

    /**
     * 注入发送层依赖。
     * @param audioSynthesisService 语音合成服务
     * @param imageContextService 图片上下文服务
     */
    public IlinkReplySender(
            AudioSynthesisService audioSynthesisService,
            ImageContextService imageContextService
    ) {
        this.audioSynthesisService =
                audioSynthesisService;
        this.imageContextService =
                imageContextService;
    }

    /**
     * 按普通文字消息入口的方式发送业务结果。
     * @param client iLink 客户端
     * @param userId 接收用户
     * @param result Processor 返回的统一回复结果
     * @param startedAt 本轮业务开始时间
     * @throws IOException 微信发送失败

     * 根据结果类型分流：
     * IMAGE → 发送图片
     * DOCUMENT → 发送文件
     * AUDIO → 合成并发送音频
     * 其他 → 发送文字
     */
    public void sendTextModeReply(
            ILinkClient client,
            String userId,
            IlinkReplyProcessor.ReplyResult result,
            long startedAt
    ) throws IOException {

        if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.IMAGE) {

            sendImageReply(
                    client,
                    userId,
                    result,
                    startedAt
            );

        } else if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.DOCUMENT) {

            sendDocumentReply(
                    client,
                    userId,
                    result,
                    startedAt
            );

        } else if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.AUDIO) {

            sendAudioAnswer(
                    client,
                    userId,
                    result.answer(),
                    startedAt
            );

            clearImageContextIfNeeded(
                    userId,
                    result
            );

        } else {
            // 普通文字结果最终在这里真正发送到微信。
            client.sendText(
                    userId,
                    result.answer()
            );

            clearImageContextIfNeeded(
                    userId,
                    result
            );

            if (result.intent()
                    == UserIntent.IMAGE_UNDERSTAND) {

                log.info(
                        "[iLink][IMAGE_UNDERSTOOD] "
                                + "toUserId={}, answer={}, "
                                + "elapsedMs={}",
                        userId,
                        formatAnswerForLog(
                                result.answer()
                        ),
                        System.currentTimeMillis()
                                - startedAt
                );

            } else {
                log.info(
                        "[iLink][REPLIED] "
                                + "toUserId={}, answer={}, "
                                + "elapsedMs={}",
                        userId,
                        formatAnswerForLog(
                                result.answer()
                        ),
                        System.currentTimeMillis()
                                - startedAt
                );
            }
        }
    }

    /**
     * 按语音消息入口的方式发送结果。
     * 图片、文件和明确音频结果按自身类型发送；
     * 普通回答仍默认发送文字，并提示支持语音回复。
     */
    public void sendVoiceModeReply(
            ILinkClient client,
            String userId,
            IlinkReplyProcessor.ReplyResult result,
            long startedAt
    ) throws IOException {

        if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.IMAGE) {

            sendImageReply(
                    client,
                    userId,
                    result,
                    startedAt
            );

        } else if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.DOCUMENT) {

            sendDocumentReply(
                    client,
                    userId,
                    result,
                    startedAt
            );

        } else if (result.type()
                == IlinkReplyProcessor
                .ReplyResultType.AUDIO) {

            sendAudioAnswer(
                    client,
                    userId,
                    result.answer(),
                    startedAt
            );

            clearImageContextIfNeeded(
                    userId,
                    result
            );

        } else {
            // 普通结果沿用文字发送逻辑。
            sendTextModeReply(
                    client,
                    userId,
                    result,
                    startedAt
            );

            // 普通文本意图额外提示用户支持语音回复。
            if (result.intent()
                    == UserIntent.TEXT) {

                sendTextQuietly(
                        client,
                        userId,
                        VOICE_CAPABILITY_TIP
                );
            }
        }
    }

    /**
     * 用户上传图片后发送确认语。
     */
    public void sendImageReceivedConfirmation(
            ILinkClient client,
            String userId
    ) {
        try {
            client.sendTextWithTyping(
                    userId,
                    "已经看到您的图片啦，您想了解什么呢？",
                    800
            );
        } catch (IOException exception) {
            log.warn(
                    "[iLink][IMAGE_CONTEXT_REPLY_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /**
     * 尝试开启微信输入状态。
     * 失败只记录日志，不影响正式回复。
     */
    public void startTypingQuietly(
            ILinkClient client,
            String userId
    ) {
        try {
            client.startTyping(userId);
        } catch (IOException exception) {
            log.warn(
                    "[iLink][TYPING_START_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /**
     * 尝试停止微信输入状态。
     * 失败只记录日志，不影响主业务。
     */
    public void stopTypingQuietly(
            ILinkClient client,
            String userId
    ) {
        try {
            client.stopTyping(userId);
        } catch (IOException exception) {
            log.warn(
                    "[iLink][TYPING_STOP_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /** 发送普通业务失败提示。 */
    public void sendFailureMessage(
            ILinkClient client,
            String userId
    ) {
        sendTextQuietly(
                client,
                userId,
                "抱歉，刚才处理失败了，请稍后再试。"
        );
    }

    /** 发送语音识别失败提示。 */
    public void sendVoiceRecognitionFailureMessage(
            ILinkClient client,
            String userId
    ) {
        sendTextQuietly(
                client,
                userId,
                "这段语音暂时没有识别出文字，请重新发一遍或改用文字。"
        );
    }

    /** 发送语音回复链路失败提示。 */
    public void sendVoiceReplyFailureMessage(
            ILinkClient client,
            String userId
    ) {
        sendTextQuietly(
                client,
                userId,
                "语音回复处理失败了，请稍后再试或改用文字。"
        );
    }

    /**
     * 发送消息排队提示。
     * 该提示只代表系统状态，
     * 不写入短期聊天和长期记忆。
     */
    public void sendQueueWaitingMessage(
            ILinkClient client,
            String userId
    ) {
        try {
            client.sendText(
                    userId,
                    QUEUE_WAITING_TIP
            );

            log.info(
                    "[iLink][QUEUE_WAITING_MESSAGE_SENT] "
                            + "userId={}",
                    userId
            );
        } catch (IOException exception) {
            log.warn(
                    "[iLink][QUEUE_WAITING_MESSAGE_SEND_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }

    /**
     * 逐个发送生成文件。
     * @throws IOException 任意一个文件发送失败时向上抛出
     */
    private void sendDocumentReply(
            ILinkClient client,
            String userId,
            IlinkReplyProcessor.ReplyResult result,
            long startedAt
    ) throws IOException {

        for (GeneratedDocument document
                : result.documents()) {

            client.sendFile(
                    userId,
                    document.bytes(),
                    document.fileName(),
                    null
            );

            log.info(
                    "[iLink][REPLY_SENT] "
                            + "userId={}, type=FILE, "
                            + "fileName={}, fileBytes={}, "
                            + "elapsedMs={}",
                    userId,
                    document.fileName(),
                    document.bytes().length,
                    System.currentTimeMillis()
                            - startedAt
            );
        }

        clearImageContextIfNeeded(
                userId,
                result
        );
    }

    /**
     * 发送图片并将新图片保存为当前图片上下文。
     * 图片保存后，用户下一轮可以继续理解或编辑该图片。
     */
    private void sendImageReply(
            ILinkClient client,
            String userId,
            IlinkReplyProcessor.ReplyResult result,
            long startedAt
    ) throws IOException {

        GeneratedImage image =
                result.image();

        // 先把图片发送给微信用户。
        client.sendImage(
                userId,
                image.bytes(),
                image.fileName(),
                null
        );

        // 发送成功后保存为新的图片上下文。
        imageContextService.save(
                userId,
                image.bytes()
        );

        log.info(
                "[iLink][REPLY_SENT] "
                        + "userId={}, type=IMAGE, "
                        + "imageBytes={}, elapsedMs={}",
                userId,
                image.bytes().length,
                System.currentTimeMillis()
                        - startedAt
        );

        // 清理 Processor 指定的旧图片上下文。
        clearImageContextIfNeeded(
                userId,
                result
        );
    }

    /**
     * 将文字答案合成为音频并发送。
     * TTS 或音频发送失败时，
     * 使用已经生成好的文字答案降级回复。
     */
    private void sendAudioAnswer(
            ILinkClient client,
            String userId,
            String answer,
            long startedAt
    ) throws IOException {

        try {
            GeneratedAudio audio =
                    audioSynthesisService
                            .synthesize(answer);

            client.sendFile(
                    userId,
                    audio.bytes(),
                    audio.fileName(),
                    null
            );

            log.info(
                    "[iLink][REPLY_SENT] "
                            + "userId={}, type=AUDIO, "
                            + "fileName={}, fileBytes={}, "
                            + "elapsedMs={}",
                    userId,
                    audio.fileName(),
                    audio.bytes().length,
                    System.currentTimeMillis()
                            - startedAt
            );

        } catch (Exception exception) {
            log.warn(
                    "[iLink][VOICE_AUDIO_REPLY_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );

            // 音频失败不丢失已有文字答案。
            client.sendText(
                    userId,
                    "语音回复生成失败了，我先用文字回复您："
                            + answer
            );
        }
    }

    /**
     * 按 ReplyResult 的要求清理旧图片上下文。
     * 只有 imageToClear 不为空时才执行。
     */
    private void clearImageContextIfNeeded(
            String userId,
            IlinkReplyProcessor.ReplyResult result
    ) {
        if (result.imageToClear() != null) {
            imageContextService.remove(
                    userId,
                    result.imageToClear()
            );
        }
    }

    /**
     * 将回答整理成适合日志记录的单行文字。
     * 1. null 转为空字符串；
     * 2. 多个换行替换为空格；
     * 3. 最多保留 1000 个字符。
     */
    private String formatAnswerForLog(
            String answer
    ) {
        if (answer == null) {
            return "";
        }

        String singleLine =
                answer.replaceAll(
                                "[\r\n]+",
                                " "
                        )
                        .trim();

        return singleLine.length() <= 1_000
                ? singleLine
                : singleLine.substring(
                0,
                1_000
        ) + "...";
    }

    /**
     * 安全发送固定文字。
     * 发送失败只记录日志，不继续向外抛异常。
     */
    private void sendTextQuietly(
            ILinkClient client,
            String userId,
            String message
    ) {
        try {
            client.sendText(
                    userId,
                    message
            );
        } catch (IOException exception) {
            log.warn(
                    "[iLink][TEXT_MESSAGE_SEND_FAILED] "
                            + "userId={}",
                    userId,
                    exception
            );
        }
    }
}