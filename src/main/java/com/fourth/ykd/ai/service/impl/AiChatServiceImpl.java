package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.service.AiChatService;


import com.fourth.ykd.ai.utils.BaiduSearchTool;
import com.fourth.ykd.ai.utils.MathCalculatorTool;
import com.fourth.ykd.ai.utils.TranslationTool;
import com.fourth.ykd.ai.utils.WeatherTool;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/* 普通文本聊天：DeepSeek 仍然是文本对话模型，只是通过 Spring AI ChatClient 调用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final String DEFAULT_CONVERSATION_ID = "api-chat";

    private final ChatClient springAiChatClient;

    private final MathCalculatorTool mathCalculatorTool;

    private final WeatherTool weatherTool;

    private final TranslationTool translationTool;

    private final BaiduSearchTool baiduSearchTool;

    @Override
    public AiChatResponse chat(String message) {
        return chat(DEFAULT_CONVERSATION_ID, message);
    }

    @Override
    public AiChatResponse chat(String conversationId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40001, "消息内容不能为空");
        }

        String normalizedMessage = message.trim();
        String normalizedConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : DEFAULT_CONVERSATION_ID;

        log.info("[AI][MEMORY_CHAT] conversationId={}", normalizedConversationId);

        String answer = springAiChatClient.prompt()
                .user(normalizedMessage)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, normalizedConversationId))
                .tools(mathCalculatorTool, weatherTool,translationTool,baiduSearchTool)
                .call()
                .content();

        return new AiChatResponse(answer);
    }



}