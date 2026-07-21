package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.infrastructure.dashscope.DashScopeProperties;
import com.fourth.ykd.ai.infrastructure.dashscope.QwenImageClient;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.exception.BusinessException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/*调 Qwen 生图，拿临时 URL，再下载成图片字节*/
@Service
public class ImageGenerationServiceImpl implements ImageGenerationService {
    private final QwenImageClient qwenImageClient;
    private final DashScopeProperties properties;
    private final RestClient imageDownloadRestClient;

    public ImageGenerationServiceImpl(QwenImageClient qwenImageClient, DashScopeProperties properties,
            @Qualifier("imageDownloadRestClient") RestClient imageDownloadRestClient) {
        this.qwenImageClient = qwenImageClient;
        this.properties = properties;
        this.imageDownloadRestClient = imageDownloadRestClient;
    }

    @Override
    public GeneratedImage generate(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40001, "图片描述不能为空");
        }
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(50003, "DASHSCOPE_API_KEY 未配置");
        }
        String imageUrl = qwenImageClient.generateImageUrl(prompt);
        // 原样保留 OSS 签名参数，不能被 HTTP 客户端二次编码。
        ResponseEntity<byte[]> response = imageDownloadRestClient.get()
                .uri(URI.create(imageUrl))
                .retrieve()
                .toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50004, "生成图片下载失败");
        }
        MediaType type = response.getHeaders().getContentType();
        return new GeneratedImage(bytes, "qwen-image.png", type == null ? MediaType.IMAGE_PNG_VALUE : type.toString());
    }
}