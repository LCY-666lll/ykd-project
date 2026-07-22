package com.fourth.ykd.ai.service.impl;

import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechModel;
import com.alibaba.cloud.ai.dashscope.audio.tts.DashScopeAudioSpeechOptions;
import com.fourth.ykd.ai.dto.GeneratedAudio;
import com.fourth.ykd.ai.service.AudioSynthesisService;
import com.fourth.ykd.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
// 语音回复业务实现，调用千问 CosyVoice 并返回可直接发送的音频字节。
public class AudioSynthesisServiceImpl implements AudioSynthesisService {

    private static final int MIN_PLAYABLE_AUDIO_BYTES = 2 * 1024;

    private final DashScopeAudioSpeechModel dashScopeAudioSpeechModel;
    private final String model;
    private final String voice;
    private final String format;
    private final Integer sampleRate;

    public AudioSynthesisServiceImpl(DashScopeAudioSpeechModel dashScopeAudioSpeechModel,
                                    @Value("${spring.ai.dashscope.audio.speech.options.model:cosyvoice-v1}") String model,
                                    @Value("${spring.ai.dashscope.audio.speech.options.voice:longhua}") String voice,
                                    @Value("${spring.ai.dashscope.audio.speech.options.response-format:mp3}") String format,
                                    @Value("${spring.ai.dashscope.audio.speech.options.sample-rate:48000}") Integer sampleRate) {
        this.dashScopeAudioSpeechModel = dashScopeAudioSpeechModel;
        this.model = model;
        this.voice = voice;
        this.format = format;
        this.sampleRate = sampleRate;
    }

    @Override
    public GeneratedAudio synthesize(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(40001, "\u8bed\u97f3\u5408\u6210\u6587\u672c\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String normalizedFormat = StringUtils.hasText(format) ? format.trim().toLowerCase(Locale.ROOT) : "mp3";
        DashScopeAudioSpeechOptions options = DashScopeAudioSpeechOptions.builder()
                .model(model).voice(voice).format(normalizedFormat).sampleRate(sampleRate).build();
        // CosyVoice 仅支持流式合成，需合并所有 MP3 分片后再交给微信发送。
        byte[] bytes = dashScopeAudioSpeechModel.stream(new TextToSpeechPrompt(text.trim(), options))
                .map(TextToSpeechResponse::getResult)
                .filter(result -> result != null && result.getOutput() != null)
                .map(result -> result.getOutput())
                .collect(ByteArrayOutputStream::new, (output, chunk) -> output.writeBytes(chunk))
                .map(ByteArrayOutputStream::toByteArray)
                .block();
        // 拦截异常短音频，避免向用户发送无法播放的损坏文件。
        if (bytes == null || bytes.length < MIN_PLAYABLE_AUDIO_BYTES) {
            throw new BusinessException(50006, "\u8bed\u97f3\u5408\u6210\u6ca1\u6709\u8fd4\u56de\u53ef\u64ad\u653e\u7684\u97f3\u9891\u5185\u5bb9");
        }
        return new GeneratedAudio(bytes, "qwen-audio-reply." + normalizedFormat, defaultContentType(normalizedFormat));
    }

    private String defaultContentType(String audioFormat) {
        return switch (audioFormat) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "pcm" -> "audio/L16";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}