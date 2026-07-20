package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.GeneratedAudio;
import com.fourth.ykd.ai.infrastructure.dashscope.DashScopeProperties;
import com.fourth.ykd.ai.infrastructure.dashscope.QwenAudioTtsClient;
import com.fourth.ykd.ai.service.AudioSynthesisService;
import com.fourth.ykd.exception.BusinessException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class AudioSynthesisServiceImpl implements AudioSynthesisService {

    private final QwenAudioTtsClient qwenAudioTtsClient;
    private final DashScopeProperties properties;
    private final RestClient mediaDownloadRestClient;

    public AudioSynthesisServiceImpl(
            QwenAudioTtsClient qwenAudioTtsClient,
            DashScopeProperties properties,
            @Qualifier("imageDownloadRestClient") RestClient mediaDownloadRestClient
    ) {
        this.qwenAudioTtsClient = qwenAudioTtsClient;
        this.properties = properties;
        this.mediaDownloadRestClient = mediaDownloadRestClient;
    }

    @Override
    public GeneratedAudio synthesize(String text) {
        validateConfiguration(text);

        String audioUrl = qwenAudioTtsClient.synthesizeAudioUrl(text);
        ResponseEntity<byte[]> response = mediaDownloadRestClient.get()
                .uri(URI.create(audioUrl))
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50006, "TTS audio download failed");
        }

        String format = properties.getTtsFormat().trim();
        MediaType type = response.getHeaders().getContentType();
        return new GeneratedAudio(
                bytes,
                "qwen-audio-reply." + format,
                type == null ? defaultContentType(format) : type.toString());
    }

    private void validateConfiguration(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(40001, "TTS text must not be blank");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(50003, "DASHSCOPE_API_KEY is not configured");
        }
        if (!StringUtils.hasText(properties.getTtsModel())) {
            throw new BusinessException(50005, "DashScope TTS model is not configured");
        }
        if (!StringUtils.hasText(properties.getTtsVoice())) {
            throw new BusinessException(50005, "DashScope TTS voice is not configured");
        }
        if (!StringUtils.hasText(properties.getTtsFormat())) {
            throw new BusinessException(50005, "DashScope TTS format is not configured");
        }
        if (properties.getTtsSampleRate() == null || properties.getTtsSampleRate() <= 0) {
            throw new BusinessException(50005, "DashScope TTS sample rate is not configured");
        }
    }

    private String defaultContentType(String format) {
        return switch (format.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "pcm" -> "audio/L16";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}