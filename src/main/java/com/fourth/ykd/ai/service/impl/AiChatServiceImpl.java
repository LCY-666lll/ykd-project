package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.ai.infrastructure.deepseek.DeepSeekClient;
import com.fourth.ykd.ai.infrastructure.deepseek.DeepSeekProperties;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;

    @Override
    public AiChatResponse chat(String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40001, "message must not be blank");
        }
        if (!StringUtils.hasText(deepSeekProperties.getApiKey())) {
            throw new BusinessException(50001, "DEEPSEEK_API_KEY is not configured");
        }
        return new AiChatResponse(deepSeekClient.chat(message.trim()));
    }
}