package com.fourth.ykd.ai.infrastructure.dashscope;

import com.fourth.ykd.ai.dto.PendingUserImage;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class QwenVisionClient {

    private static final String CHAT_COMPLETIONS_PATH = "/compatible-mode/v1/chat/completions";

    private final RestClient dashScopeRestClient;
    private final DashScopeProperties properties;

    public String understand(PendingUserImage image, String question) {
        if (image == null || image.bytes() == null || image.bytes().length == 0) {
            throw new IllegalArgumentException("待识别图片不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("识图问题不能为空");
        }

        String imageDataUrl = "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
        Map<String, Object> requestBody = Map.of(
                "model", properties.getVisionModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)),
                                Map.of("type", "text", "text", question.trim())
                        )
                ))
        );

        JsonNode response = dashScopeRestClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        String answer = response == null
                ? null
                : response.path("choices").path(0).path("message").path("content").asText();
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("Qwen-VL 没有返回识图结果");
        }
        return answer.trim();
    }
}