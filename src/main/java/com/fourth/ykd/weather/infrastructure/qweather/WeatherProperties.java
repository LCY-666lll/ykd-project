package com.fourth.ykd.weather.infrastructure.qweather;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/* 天气Api配置类，负责从配置文件统一读配置
application.properties
        ↓ Spring 自动绑定
WeatherProperties
        ↓
后面的 HTTP 客户端、天气服务共同使用*/
@Getter
@Setter
@ConfigurationProperties(prefix = "weather.qweather")
public class WeatherProperties {

    private String apiHost;

    private String apiKey;

    private int connectTimeoutMs;

    private int readTimeoutMs;

}