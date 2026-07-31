package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.AiChatResponse;

public interface AiChatService {

    AiChatResponse chat(String message);

    AiChatResponse chat(String conversationId, String message);

    /**
     * 同步执行用户明确提出的长期记忆新增、纠正或删除请求。
     * 返回前必须完成实际数据库操作，供机器人根据真实结果回复。
     */
    AiChatResponse manageMemory(String conversationId, String message);

    String prepareImagePrompt(String conversationId, String userText);
}
