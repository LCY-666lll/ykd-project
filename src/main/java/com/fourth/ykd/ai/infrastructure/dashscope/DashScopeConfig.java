package com.fourth.ykd.ai.infrastructure.dashscope;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeConfig {

    @Bean
    public RestClient dashScopeRestClient(DashScopeProperties properties) {
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getApiBaseUrl());
        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
        }
        return builder.build();
    }

    @Bean
    public RestClient imageDownloadRestClient() {
        // 下载临时图片时不能把百炼密钥发送给 OSS。
        return RestClient.create();
    }
}