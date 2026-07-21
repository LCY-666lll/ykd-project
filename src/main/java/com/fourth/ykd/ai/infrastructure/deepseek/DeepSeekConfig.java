package com.fourth.ykd.ai.infrastructure.deepseek;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/*创建带 Authorization Header 的 deepSeekRestClient
DeepSeek 有两个用途：
1. 路由判断：DeepSeekIntentRouter -> DeepSeekClient
2. 普通聊天兜底：AiChatServiceImpl -> DeepSeekClient
 ***普通聊天主链路现在优先走 Spring AI starter 创建的 DeepSeek ChatModel，不是这个自写客户端。*/
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
public class DeepSeekConfig {

    @Bean
    public RestClient deepSeekRestClient(DeepSeekProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getApiBaseUrl());
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        return builder.build();
    }
}