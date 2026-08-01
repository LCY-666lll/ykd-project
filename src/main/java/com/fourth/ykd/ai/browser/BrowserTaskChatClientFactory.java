package com.fourth.ykd.ai.browser;

import com.fourth.ykd.ai.infrastructure.memory.SqliteChatMessageRepository;
import com.fourth.ykd.ai.trace.ReActTraceAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 为每个浏览器任务创建独立 ChatClient。
 * 不复用普通聊天的长期记忆和主会话短期记忆。
 */
@Component
public class BrowserTaskChatClientFactory {
    private static final int BROWSER_MAX_TOOL_ROUNDS = 16;

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final SqliteChatMessageRepository sqliteChatMessageRepository;

    public BrowserTaskChatClientFactory(
            ChatModel chatModel,
            ToolCallingManager toolCallingManager,
            SqliteChatMessageRepository sqliteChatMessageRepository
    ) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.sqliteChatMessageRepository = sqliteChatMessageRepository;
    }

    public ChatClient create(ChatMemory taskMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        new ReActTraceAdvisor(
                                toolCallingManager,
                                Ordered.HIGHEST_PRECEDENCE + 300,
                                taskMemory,
                                sqliteChatMessageRepository,
                                BROWSER_MAX_TOOL_ROUNDS
                        )
                )
                .build();
    }
}