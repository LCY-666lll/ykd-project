package com.fourth.ykd.ai.infrastructure.deepseek;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    private String apiBaseUrl;

    private String apiKey;

    private String model;
}