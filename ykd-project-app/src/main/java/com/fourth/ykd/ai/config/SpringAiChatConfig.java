package com.fourth.ykd.ai.config;

import com.fourth.ykd.ai.rag.SQLiteVectorStore;
import com.fourth.ykd.ai.service.liepinToolCallingManager;
import com.fourth.ykd.ai.service.McpToolService;
import com.fourth.ykd.ai.trace.ReActTraceAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/*创建一个自带“聊天记忆功能”的 ChatClient（创建和装配 AI 基础 Bean），以后调用它时，会自动把历史消息带给大模型。
项目启动
  ↓
Spring 找到 ChatModel
  ↓
执行 aiMemoryChatClient()
  ↓
给 ChatClient 添加聊天记忆 Advisor
  ↓
生成 ChatClient Bean 放进 Spring 容器
  ↓
业务层注入并使用*/
@Configuration
public class SpringAiChatConfig {

    /** RAG 向量存储 Bean，基于 SQLite 持久化。 */
    @Bean
    public SQLiteVectorStore vectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel, JdbcTemplate jdbcTemplate) {
        return new SQLiteVectorStore(embeddingModel, jdbcTemplate);
    }

    @Bean
    public ToolCallingManager toolCallingManager(McpToolService mcpToolService,
            org.springframework.ai.tool.resolution.ToolCallbackResolver toolCallbackResolver) {
        DefaultToolCallingManager defaultManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(toolCallbackResolver)
                .build();
        return new liepinToolCallingManager(defaultManager, mcpToolService);
    }

    @Bean
    public ChatClient aiMemoryChatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
            ToolCallingManager toolCallingManager) {

        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory)
                        .order(Ordered.HIGHEST_PRECEDENCE + 200)
                        .build();

        chatClientBuilder.defaultAdvisors(memoryAdvisor,
                new ReActTraceAdvisor(toolCallingManager, Ordered.HIGHEST_PRECEDENCE + 300));

        return chatClientBuilder.build();
    }
}
