package com.fourth.ykd.ai.service.impl;

import com.alibaba.cloud.ai.dashscope.sdk.image.DashScopeSdkImageOptions;
import com.fourth.ykd.ai.dto.GeneratedImage;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.service.ImageReferenceGenerationService;
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
// 图生图业务实现，将用户图片作为千问官方图片模型的参考图。
public class ImageReferenceGenerationServiceImpl implements ImageReferenceGenerationService {

    private final ImageModel imageModel;
    private final RestClient imageDownloadRestClient;

    public ImageReferenceGenerationServiceImpl(ImageModel imageModel,
                                               RestClient.Builder restClientBuilder) {
        this.imageModel = imageModel;
        this.imageDownloadRestClient = restClientBuilder.build();
    }

    @Override
    public GeneratedImage generate(PendingUserImage referenceImage, String prompt) {
        if (referenceImage == null || referenceImage.bytes() == null || referenceImage.bytes().length == 0) {
            throw new BusinessException(40001, "\u53c2\u8003\u56fe\u7247\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new BusinessException(40001, "\u56fe\u7247\u7f16\u8f91\u6307\u4ee4\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String contentType = StringUtils.hasText(referenceImage.contentType()) ? referenceImage.contentType() : MediaType.IMAGE_PNG_VALUE;
        // 将微信缓存的图片字节转为 SDK refImage 所需的数据地址。
        String referenceImageDataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(referenceImage.bytes());
        ImageResponse response = imageModel.call(new ImagePrompt(prompt.trim(),
                DashScopeSdkImageOptions.builder().refImage(referenceImageDataUrl).async(false).build()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new BusinessException(50004, "\u56fe\u751f\u56fe\u6ca1\u6709\u8fd4\u56de\u56fe\u7247\u5185\u5bb9");
        }
        String imageUrl = response.getResult().getOutput().getUrl();
        if (StringUtils.hasText(imageUrl)) {
            // URL 结果统一下载为图片字节，保持 iLink 发送流程不变。
            return downloadImage(imageUrl);
        }
        String b64Json = response.getResult().getOutput().getB64Json();
        if (!StringUtils.hasText(b64Json)) {
            throw new BusinessException(50004, "\u56fe\u751f\u56fe\u6ca1\u6709\u8fd4\u56de\u56fe\u7247\u5185\u5bb9");
        }
        return new GeneratedImage(Base64.getDecoder().decode(b64Json), "qwen-image-reference.png", MediaType.IMAGE_PNG_VALUE);
    }

    private GeneratedImage downloadImage(String imageUrl) {
        ResponseEntity<byte[]> response = imageDownloadRestClient.get().uri(URI.create(imageUrl)).retrieve().toEntity(byte[].class);
        byte[] bytes = response.getBody();
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(50004, "\u56fe\u751f\u56fe\u7ed3\u679c\u4e0b\u8f7d\u5931\u8d25");
        }
        MediaType contentType = response.getHeaders().getContentType();
        return new GeneratedImage(bytes, "qwen-image-reference.png", contentType == null ? MediaType.IMAGE_PNG_VALUE : contentType.toString());
    }
}