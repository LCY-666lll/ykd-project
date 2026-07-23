package com.fourth.ykd.weather.infrastructure.qweather;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherConfig {

    private static final String DEFAULT_API_HOST = "p33tejmexe.re.qweatherapi.com";

    @Bean
    public RestClient qWeatherRestClient(WeatherProperties properties) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(
                        Timeout.ofMilliseconds(properties.getConnectTimeoutMs())
                )
                .setResponseTimeout(
                        Timeout.ofMilliseconds(properties.getReadTimeoutMs())
                )
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();

        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        String apiHost = StringUtils.hasText(properties.getApiHost())
                ? properties.getApiHost().trim()
                : DEFAULT_API_HOST;

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://" + apiHost)
                .requestFactory(requestFactory);

        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader("X-QW-Api-Key", properties.getApiKey());
        }

        return builder.build();
    }
}