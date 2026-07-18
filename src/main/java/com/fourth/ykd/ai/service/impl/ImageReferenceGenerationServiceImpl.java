package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.infrastructure.dashscope.DashScopeProperties;
import com.fourth.ykd.ai.infrastructure.dashscope.QwenImageClient;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
import com.fourth.ykd.exception.BusinessException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class ImageReferenceGenerationServiceImpl implements ImageReferenceGenerationService {

    private final QwenImageClient qwenImageClient;
    private final DashScopeProperties properties;
    private final RestClient imageDownloadRestClient;

    public ImageReferenceGenerationServiceImpl(
            QwenImageClient qwenImageClient,
            DashScopeProperties properties,
            @Qualifier("imageDownloadRestClient") RestClient imageDownloadRestClient
    ) {
        this.qwenImageClient = qwenImageClient;
        this.properties = properties;
        this.imageDownloadRestClient = imageDownloadRestClient;
    }

    @Override
    public GeneratedImage generate(PendingUserImage referenceImage, String prompt) {
        if (referenceImage == null || referenceImage.bytes() == null || referenceImage.bytes().length == 0) {
            throw new BusinessException(40001, "参考图片不能为空");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40001, "图片编辑指令不能为空");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(50003, "DASHSCOPE_API_KEY 未配置");
        }

        String imageUrl = qwenImageClient.generateImageUrl(referenceImage, prompt);
        ResponseEntity<byte[]> response = imageDownloadRestClient.get()
                .uri(URI.create(imageUrl))
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50004, "参考图生成结果下载失败");
        }

        MediaType type = response.getHeaders().getContentType();
        return new GeneratedImage(bytes, "qwen-image-reference.png",
                type == null ? MediaType.IMAGE_PNG_VALUE : type.toString());
    }
}