package com.fourth.ykd.ai.service.impl;

import com.alibaba.cloud.ai.dashscope.sdk.image.DashScopeSdkImageOptions;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.service.ImageGenerationService;
import com.fourth.ykd.exception.BusinessException;
import java.net.URI;
import java.util.Base64;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
// 文生图业务实现，使用 Spring AI Alibaba 官方图片模型生成并返回图片字节。
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private final ImageModel imageModel;
    private final RestClient imageDownloadRestClient;

    public ImageGenerationServiceImpl(ImageModel imageModel,
                                      RestClient.Builder restClientBuilder) {
        this.imageModel = imageModel;
        this.imageDownloadRestClient = restClientBuilder.build();
    }

    @Override
    public GeneratedImage generate(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40001, "\u56fe\u7247\u63cf\u8ff0\u4e0d\u80fd\u4e3a\u7a7a");
        }
        // 使用官方 SDK 的同步模式，当前账号可直接取得生成结果。
        ImageResponse response = imageModel.call(new ImagePrompt(prompt.trim(),
                DashScopeSdkImageOptions.builder().async(false).build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new BusinessException(50004, "\u56fe\u7247\u751f\u6210\u6ca1\u6709\u8fd4\u56de\u56fe\u7247\u5185\u5bb9");
        }
        String imageUrl = response.getResult().getOutput().getUrl();
        if (StringUtils.hasText(imageUrl)) {
            // 部分模型返回临时地址，需要下载为字节后再发送给 iLink。
            return downloadImage(imageUrl, "qwen-image.png");
        }
        // SDK 也可能直接返回 Base64 图片内容。
        String b64Json = response.getResult().getOutput().getB64Json();
        if (!StringUtils.hasText(b64Json)) {
            throw new BusinessException(50004, "\u56fe\u7247\u751f\u6210\u6ca1\u6709\u8fd4\u56de\u56fe\u7247\u5185\u5bb9");
        }
        return new GeneratedImage(Base64.getDecoder().decode(b64Json), "qwen-image.png", MediaType.IMAGE_PNG_VALUE);
    }

    private GeneratedImage downloadImage(String imageUrl, String fileName) {
        ResponseEntity<byte[]> response = imageDownloadRestClient.get().uri(URI.create(imageUrl)).retrieve().toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50004, "\u751f\u6210\u56fe\u7247\u4e0b\u8f7d\u5931\u8d25");
        }
        MediaType contentType = response.getHeaders().getContentType();
        return new GeneratedImage(bytes, fileName, contentType == null ? MediaType.IMAGE_PNG_VALUE : contentType.toString());
    }
}