package com.fourth.ykd.ai.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.protocol.Protocol;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.exception.BusinessException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
// 图片编辑业务实现，仅此链路使用千问官方同步多模态编辑接口。
public class ImageReferenceGenerationServiceImpl implements ImageReferenceGenerationService {

    private final MultiModalConversation imageEditClient;
    private final RestClient imageDownloadRestClient;
    private final String apiKey;
    private final String imageEditModel;

    public ImageReferenceGenerationServiceImpl(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${spring.ai.dashscope.image.edit-model:qwen-image-edit}") String imageEditModel) {
        this.imageEditClient = new MultiModalConversation(Protocol.HTTP.getValue(), normalizeSdkBaseUrl(baseUrl));
        this.imageDownloadRestClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.imageEditModel = imageEditModel;
    }

    private static String normalizeSdkBaseUrl(String baseUrl) {
        String normalized = StringUtils.hasText(baseUrl)
                ? baseUrl.trim().replaceAll("/+$", "")
                : "https://dashscope.aliyuncs.com";
        return normalized.endsWith("/api/v1") ? normalized : normalized + "/api/v1";
    }

    @Override
    public GeneratedImage generate(PendingUserImage referenceImage, String prompt) {
        if (referenceImage == null || referenceImage.bytes() == null || referenceImage.bytes().length == 0) {
            throw new BusinessException(40001, "参考图片不能为空");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40001, "图片编辑指令不能为空");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new BusinessException(50004, "图片编辑服务未配置 DashScope API Key");
        }

        String contentType = StringUtils.hasText(referenceImage.contentType())
                ? referenceImage.contentType()
                : MediaType.IMAGE_PNG_VALUE;
        String referenceImageDataUrl = "data:" + contentType + ";base64,"
                + Base64.getEncoder().encodeToString(referenceImage.bytes());
        String referencePrompt = """
                严格以用户提供的原图为编辑基础。保留用户未要求改变的主体、人物身份、外观、姿态、构图和其他画面内容，
                只修改用户明确指定的部分，不得重新创作或替换为无关人物、物体或场景。
                用户编辑指令：%s
                """.formatted(prompt.trim());

        MultiModalMessage message = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(
                        Map.of("image", referenceImageDataUrl),
                        Map.of("text", referencePrompt)))
                .build();
        MultiModalConversationParam request = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                .model(imageEditModel)
                .message(message)
                .parameter("n", 1)
                .build();

        try {
            MultiModalConversationResult response = imageEditClient.call(request);
            return downloadImage(extractImageUrl(response));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(50004, "图片编辑模型调用失败：" + exception.getMessage());
        }
    }

    private String extractImageUrl(MultiModalConversationResult response) {
        if (response == null || response.getOutput() == null || response.getOutput().getChoices() == null) {
            throw new BusinessException(50004, "图片编辑模型没有返回图片内容");
        }
        for (var choice : response.getOutput().getChoices()) {
            if (choice.getMessage() == null || choice.getMessage().getContent() == null) {
                continue;
            }
            for (Map<String, Object> content : choice.getMessage().getContent()) {
                Object image = content.get("image");
                if (image instanceof String imageUrl && StringUtils.hasText(imageUrl)) {
                    return imageUrl;
                }
            }
        }
        throw new BusinessException(50004, "图片编辑模型没有返回图片地址");
    }

    private GeneratedImage downloadImage(String imageUrl) {
        ResponseEntity<byte[]> response = imageDownloadRestClient.get()
                .uri(URI.create(imageUrl))
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50004, "图片编辑结果下载失败");
        }
        MediaType contentType = response.getHeaders().getContentType();
        return new GeneratedImage(bytes, "qwen-image-edit.png",
                contentType == null ? MediaType.IMAGE_PNG_VALUE : contentType.toString());
    }
}
