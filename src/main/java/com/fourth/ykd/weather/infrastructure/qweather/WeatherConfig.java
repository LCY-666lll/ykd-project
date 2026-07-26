package com.fourth.ykd.weather.infrastructure.qweather;

import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
/*在程序启动时创建一次专用客户端，后续重复使用
项目启动时，提前创建并配置好一个专门请求和风天气 API 的 RestClient，后面的业务类直接注入使用，不用每次请求都重新创建。*/
@Configuration
@RequiredArgsConstructor
public class WeatherConfig {

    private static final String DEFAULT_API_HOST = "p33tejmexe.re.qweatherapi.com";

    private final WeatherProperties properties;

    @Bean
    public RestClient qWeatherRestClient() {

        /*RequestConfig 是 Apache HttpClient 提供的请求配置类。
        构造底层超时配置:主要用来设置：连接超时 响应超时 请求相关参数*/
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(
                        Timeout.ofMilliseconds(properties.getConnectTimeoutMs())
                )
                .setResponseTimeout(
                        Timeout.ofMilliseconds(properties.getReadTimeoutMs())
                )
                .build();

        /*HttpClient 是 Apache HttpClient 中可以发送 HTTP 请求的客户端制造厂
        根据配置创建 Apache HttpClient,创建真正负责网络通信的底层客户端, 至此形成：
        WeatherProperties → RequestConfig → CloseableHttpClient*/
        CloseableHttpClient httpClient = HttpClients.custom()
                //以后这个 HttpClient 发出的请求，默认都使用这套超时配置
                .setDefaultRequestConfig(requestConfig)
                .build();

        /*HttpComponentsClientHttpRequestFactory
        这个类属于 Spring，不属于 Apache，把 Apache 的 CloseableHttpClient 转换成 Spring HTTP 客户端能够使用的请求工厂。
        创建请求工厂，Spring 的 RestClient 不直接要求业务代码操作 Apache HttpClient。
        它通过 Spring 自己的请求工厂适配底层客户端：
        Apache CloseableHttpClient
        → 包装成 Spring 可以使用的 ClientHttpRequestFactory
        → 交给 RestClient
        QWeatherClient 只需要使用 Spring API：qWeatherRestClient.get()
        不需知道底层采用的是 Apache HttpClient。*/
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);


        String apiHost = StringUtils.hasText(properties.getApiHost())
                ? properties.getApiHost().trim()
                : DEFAULT_API_HOST;

        //开始构造Spring RestClient
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://" + apiHost)
                //告诉 RestClient：所有和风天气请求都通过前面配置好的 Apache HttpClient 发送
                .requestFactory(requestFactory);

        if (StringUtils.hasText(properties.getApiKey())) {
            builder.defaultHeader("X-QW-Api-Key", properties.getApiKey());
        }

        /*此时返回的 RestClient 已经拥有：
        基础地址 https://p33tejmexe.re.qweatherapi.com
        认证 X-QW-Api-Key
        网络限制 连接超时 5000ms 响应超时 5000ms
        底层实现 Apache HttpClient*/
        return builder.build();
    }
}