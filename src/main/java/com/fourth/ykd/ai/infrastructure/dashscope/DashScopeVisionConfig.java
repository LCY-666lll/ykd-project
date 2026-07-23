package com.fourth.ykd.ai.infrastructure.dashscope;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// 千问图片识别模型配置，单独创建兼容端点模型以保留 DeepSeek 默认文本模型。
public class       DashScopeVisionConfig {

    @Bean("dashScopeVisionModel")
    public DashScopeVisionModelProvider dashScopeVisionModel(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-vl-plus}") String model
    ) {
        // DashScope 的兼容端点可直接接收 Spring AI Media 中的本地图片字节。
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl + "/compatible-mode")
                .build();
        // 独立创建识图模型，避免覆盖默认的 DeepSeek 文本 ChatClient。
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
        return new DashScopeVisionModelProvider(chatModel);
    }
}