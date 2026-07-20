package com.fourth.ykd.ai.service.impl;

import com.fourth.ykd.ai.dto.AiChatResponse;
import com.fourth.ykd.ai.infrastructure.deepseek.DeepSeekClient;
import com.fourth.ykd.ai.infrastructure.deepseek.DeepSeekProperties;
import com.fourth.ykd.ai.service.AiChatService;
import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private static final String DEFAULT_CONVERSATION_ID = "api-chat";

    private final DeepSeekClient deepSeekClient;
    private final DeepSeekProperties deepSeekProperties;
    private final ChatClient springAiChatClient;

    @Override
    public AiChatResponse chat(String message) {
        return chat(DEFAULT_CONVERSATION_ID, message);
    }

   /* 校验参数 → 整理参数 → 优先使用带记忆的 Spring AI → Spring AI 没配置时使用旧的 DeepSeekClient 兜底
    用户传入 conversationId 和 message
        ↓
    检查 message 是否为空
        ↓
    检查 DeepSeek API Key
        ↓
    去掉参数前后空格
        ↓
    尝试从 Spring 容器获取 ChatClient
        ↓
    获取成功？
    是          否
    ↓          ↓
    Spring AI   DeepSeekClient
    带记忆聊天    普通聊天兜底
    ↓           ↓
    封装成 AiChatResponse 返回*/
    @Override
    public AiChatResponse chat(String conversationId, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40001, "message must not be blank");
        }
        if (!StringUtils.hasText(deepSeekProperties.getApiKey())) {
            throw new BusinessException(50001, "DEEPSEEK_API_KEY is not configured");
        }

        String normalizedMessage = message.trim();
        //用户传了会话 ID，就使用用户传的；没传就使用默认会话 ID
        String normalizedConversationId = StringUtils.hasText(conversationId)
                ? conversationId.trim()
                : DEFAULT_CONVERSATION_ID;
        if (springAiChatClient != null) {
            log.info("[AI][MEMORY_CHAT] conversationId={}", normalizedConversationId);
            /*chatClient.prompt() : 构造一次请求：开始准备一次发送给大模型的 Prompt
            1. 创建本次 AI 请求
            2. 设置用户问题 user = "我叫什么？"
            3. 设置本次 Advisor 参数 conversationId = "conversation-001"
            4. 执行 ChatClient 中已经注册的 MessageChatMemoryAdvisor
            5. MessageChatMemoryAdvisor 读取 conversationId
            6. 根据 conversation-001 查询历史消息
            7. 历史消息 + 当前问题发送给模型
            8. 把当前问题和回答继续存入 conversation-001*/
            String answer = springAiChatClient.prompt()
                    .user(normalizedMessage)
                    /*.advisors() 方法内部会创建一个 AdvisorSpec 对象，然后传给 Lambda。
                     advisorSpec = Advisor 参数配置器
                     MessageChatMemoryAdvisor = 真正执行记忆逻辑的 Advisor
                     设置本次会话ID:使用聊天记忆 Advisor 时，每次调用都必须通过 .param() 传入 ChatMemory.CONVERSATION_ID，否则运行时会抛出异常*/
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, normalizedConversationId))
                     /*spring-ai-starter-model-deepseek
                                   ↓
                     自动创建 DeepSeekChatModel
                                   ↓
                     ChatClient.Builder 使用这个 ChatModel
                                   ↓
                     chatClient.call()
                                   ↓
                     请求 DeepSeek*/
                    .call()
                    .content();
            return new AiChatResponse(answer);
        }

        log.warn("[AI][MEMORY_CHAT_UNAVAILABLE] fallbackToDeepSeek=true, conversationId={}",
                normalizedConversationId);

        //兜底逻辑
        return new AiChatResponse(deepSeekClient.chat(normalizedMessage));
    }
}
