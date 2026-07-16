package com.fourth.ykd.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

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