package com.fourth.ykd.ai.service.impl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.infrastructure.dashscope.DashScopeVisionModelProvider;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.exception.BusinessException;
import java.util.List;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

@Service
// 图片识别业务实现，使用千问视觉模型回答用户关于当前图片的问题。
public class ImageUnderstandingServiceImpl implements ImageUnderstandingService {

    private final DashScopeVisionModelProvider dashScopeVisionModelProvider;

    public ImageUnderstandingServiceImpl(@Qualifier("dashScopeVisionModel") DashScopeVisionModelProvider dashScopeVisionModelProvider) {
        this.dashScopeVisionModelProvider = dashScopeVisionModelProvider;
    }

    @Override
    public String understand(PendingUserImage image, String question) {
        if (image == null || image.bytes() == null || image.bytes().length == 0) {
            throw new BusinessException(40001, "\u5f85\u8bc6\u522b\u56fe\u7247\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(40001, "\u8bc6\u56fe\u95ee\u9898\u4e0d\u80fd\u4e3a\u7a7a");
        }
        MimeType mimeType = StringUtils.hasText(image.contentType()) ? MimeTypeUtils.parseMimeType(image.contentType()) : MimeTypeUtils.IMAGE_PNG;
        // Spring AI 会把图片字节按兼容端点要求编码为多模态消息。
        UserMessage userMessage = UserMessage.builder().text(question.trim())
                .media(List.of(Media.builder().mimeType(mimeType).data(image.bytes()).build())).build();
        // 使用专用千问识图模型，普通文本对话仍由 DeepSeek 处理。
        String answer = dashScopeVisionModelProvider.model()
                .call(new Prompt(userMessage, DashScopeChatOptions.builder().build()))
                .getResult().getOutput().getText();
        if (!StringUtils.hasText(answer)) {
            throw new BusinessException(50004, "\u56fe\u7247\u7406\u89e3\u6ca1\u6709\u8fd4\u56de\u7ed3\u679c");
        }
        return answer.trim();
    }
}