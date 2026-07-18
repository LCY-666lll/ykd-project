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

/**
 * 通义千问图片生成客户端。
 * 主要作用：图片描述 → 调用 DashScope → 得到临时图片 URL
 * 1. 接收用户输入的图片描述；
 * 2. 调用 DashScope 的 Qwen-Image 图片生成接口；
 * 3. 从响应结果中提取生成图片的临时 URL；
 * 4. 将图片 URL 返回给上层业务。
 */
@Component
@RequiredArgsConstructor
public class QwenImageClient {

    /**
     * DashScope 图片生成接口路径。
     */
    private static final String GENERATION_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation";

    /**
     * 用于向 DashScope 发送 HTTP 请求。
     * 该对象通常已经提前配置好：
     * 1. DashScope 基础地址；
     * 2. API Key；
     * 3. Authorization 请求头；
     * 4. 请求超时时间。
     */
    private final RestClient dashScopeRestClient;

    /**
     * DashScope 配置对象:
     * 用于读取配置文件中的图片生成模型名称等配置。
     */
    private final DashScopeProperties properties;

    /**
     * 调用同步版 Qwen-Image 图片生成接口，
     * 返回生成图片的临时访问 URL。
     * @param prompt 用户输入的图片描述词
     * @return 图片生成接口返回的临时图片 URL
     */
    public String generateImageUrl(PendingUserImage referenceImage, String prompt) {
        if (referenceImage == null || referenceImage.bytes() == null || referenceImage.bytes().length == 0) {
            throw new IllegalArgumentException("参考图片不能为空");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("图片编辑指令不能为空");
        }

        String imageDataUrl = "data:" + referenceImage.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(referenceImage.bytes());
        Map<String, Object> requestBody = Map.of(
                "model", properties.getImageGenerationModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("image", imageDataUrl),
                                        Map.of("text", prompt.trim())
                                )
                        ))
                ),
                "parameters", Map.of(
                        "size", "1024*1024",
                        "prompt_extend", true,
                        "watermark", false
                )
        );

        return requestImageUrl(requestBody);
    }
    public String generateImageUrl(String prompt) {

        // 判断图片描述是否为 null、空字符串或全部为空格
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("图片生成提示词不能为空");
        }

        // 构造发送给 DashScope 的 JSON 请求体
        Map<String, Object> requestBody = Map.of(

                // 指定使用的图片生成模型
                "model", properties.getImageGenerationModel(),

                // 模型输入内容
                "input", Map.of(
                        // 对话消息列表
                        "messages", List.of(Map.of(

                                // 当前消息的角色是用户
                                "role", "user",

                                // 用户输入的具体内容
                                "content", List.of(Map.of(

                                        // 去掉提示词前后的多余空格
                                        "text", prompt.trim()
                                ))
                        ))
                ),

                // 图片生成参数
                "parameters", Map.of(

                        // 设置图片尺寸为 1024 × 1024
                        "size", "1024*1024",

                        // 是否允许模型自动扩展和优化提示词
                        "prompt_extend", true,

                        // 是否在生成图片中添加水印
                        "watermark", false
                )
        );

        // 发送 POST 请求调用图片生成接口
        return requestImageUrl(requestBody);
    }
    private String requestImageUrl(Map<String, Object> requestBody) {
        JsonNode response = dashScopeRestClient.post()
                .uri(GENERATION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Qwen-Image 返回了空响应");
        }

        String imageUrl = response.path("output")
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .path(0)
                .path("image")
                .asText();
        if (!StringUtils.hasText(imageUrl)) {
            throw new IllegalStateException("Qwen-Image 没有返回图片 URL");
        }
        return imageUrl;
    }
}