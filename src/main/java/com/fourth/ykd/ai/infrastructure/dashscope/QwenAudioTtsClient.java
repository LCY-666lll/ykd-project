package com.fourth.ykd.ai.infrastructure.dashscope;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
/*DashScope TTS HTTP 客户端。*/
@Component
@RequiredArgsConstructor
public class QwenAudioTtsClient {

    private static final String TTS_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer";

    private final RestClient dashScopeRestClient;
    private final DashScopeProperties properties;

    public String synthesizeAudioUrl(String text) {
        validateRequest(text);

        Map<String, Object> requestBody = Map.of(
                "model", properties.getTtsModel().trim(),
                "input", Map.of(
                        "text", text.trim(),
                        "voice", properties.getTtsVoice().trim(),
                        "format", properties.getTtsFormat().trim(),
                        "sample_rate", properties.getTtsSampleRate()
                )
        );

        JsonNode response = dashScopeRestClient.post()
                .uri(TTS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen-Audio-TTS returned an empty response");
        }

        String audioUrl = response.path("output").path("audio").path("url").asText();
        if (!StringUtils.hasText(audioUrl)) {
            audioUrl = response.path("output").path("url").asText();
        }
        if (!StringUtils.hasText(audioUrl)) {
            audioUrl = response.path("output").path("audio_url").asText();
        }
        if (!StringUtils.hasText(audioUrl)) {
            throw new IllegalStateException("Qwen-Audio-TTS did not return an audio URL");
        }
        return audioUrl;
    }

    private void validateRequest(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("TTS text must not be blank");
        }
        if (!StringUtils.hasText(properties.getTtsModel())) {
            throw new IllegalStateException("DashScope TTS model is not configured");
        }
        if (!StringUtils.hasText(properties.getTtsVoice())) {
            throw new IllegalStateException("DashScope TTS voice is not configured");
        }
        if (!StringUtils.hasText(properties.getTtsFormat())) {
            throw new IllegalStateException("DashScope TTS format is not configured");
        }
        if (properties.getTtsSampleRate() == null || properties.getTtsSampleRate() <= 0) {
            throw new IllegalStateException("DashScope TTS sample rate is not configured");
        }
    }
}