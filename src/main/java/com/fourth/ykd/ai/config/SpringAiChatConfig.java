package com.fourth.ykd.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*创建一个自带“聊天记忆功能”的 ChatClient，以后调用它时，会自动把历史消息带给大模型。
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

    @Bean
    //ChatClient.Builder : Spring AI 根据当前配置好的 ChatModel，自动准备好这个 Builder
    public ChatClient aiMemoryChatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {

        /*MessageChatMemoryAdvisor 是一个聊天记忆顾问，也可以理解成一个拦截器。
        它会在调用大模型前后自动做两件事：
        请求大模型之前：从 ChatMemory 查询历史消息，并加入当前请求
        大模型返回之后：把本次用户问题和 AI 回答保存到 ChatMemory
        业务代码不需要每次手动查询和保存历史记录。
        它帮你自动完成：读取历史消息 → 拼接当前问题 → 调用模型 → 保存本轮对话
        MessageChatMemoryAdvisor ：负责从记忆中读取历史消息，将这些消息作为消息集合加入Prompt的组件
        使用这个 chatMemory，创建一个负责聊天记忆的 Advisor：

        这个 advisor 会在每次调用模型之前执行。
        执行时它会拿到：
        ChatMemory.CONVERSATION_ID = userId
        然后内部大概等价于：
        List<Message> history = chatMemory.get(userId);
        */
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory).build();

// 注册到 Builder 内部
        chatClientBuilder.defaultAdvisors(memoryAdvisor);

// 构建 ChatClient
        return chatClientBuilder.build();
    }
}
