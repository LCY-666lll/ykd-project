package com.fourth.ykd.ai.service;

import com.fourth.ykd.ai.dto.AiChatResponse;

public interface AiChatService {

    AiChatResponse chat(String message);

    AiChatResponse chat(String conversationId, String message);
}
