package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.PendingUserImage;
import com.fourth.ykd.ai.infrastructure.dashscope.DashScopeProperties;
import com.fourth.ykd.ai.infrastructure.dashscope.QwenVisionClient;
import com.fourth.ykd.ai.service.ImageUnderstandingService;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ImageUnderstandingServiceImpl implements ImageUnderstandingService {

    private final QwenVisionClient qwenVisionClient;
    private final DashScopeProperties properties;

    @Override
    public String understand(PendingUserImage image, String question) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException(50001, "DASHSCOPE_API_KEY is not configured");
        }
        return qwenVisionClient.understand(image, question);
    }
}