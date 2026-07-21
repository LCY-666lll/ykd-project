package com.fourth.ykd.ai.infrastructure.deepseek;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/*DeepSeek HTTP 客户端。路由器用它，Spring AI 不可用时聊天兜底也用它。*/
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private final RestClient deepSeekRestClient;
    private final DeepSeekProperties deepSeekProperties;

    public String chat(String message) {
        Map<String, Object> requestBody = Map.of(
                "model", deepSeekProperties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", message))
        );

        JsonNode response = deepSeekRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("DeepSeek returned an empty response");
        }

        String reply = response.path("choices").path(0).path("message").path("content").asText();
        if (reply.isBlank()) {
            throw new IllegalStateException("DeepSeek did not return a chat reply");
        }
        return reply;
    }
}