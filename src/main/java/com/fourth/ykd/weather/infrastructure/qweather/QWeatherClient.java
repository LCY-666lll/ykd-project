package com.fourth.ykd.weather.infrastructure.qweather;

import com.fourth.ykd.weather.infrastructure.qweather.dto.QWeatherCityLookupResponse;
import com.fourth.ykd.weather.infrastructure.qweather.dto.QWeatherNowResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/*向和风天气发 HTTP 请求
        ↓
把 JSON 自动转为 QWeatherCityLookupResponse
        ↓
交给后面的 WeatherService
*/
@Slf4j
@Component
public class QWeatherClient {

    private final RestClient qWeatherRestClient;

    public QWeatherClient(RestClient qWeatherRestClient) {
        this.qWeatherRestClient = qWeatherRestClient;
    }
    /**
     * 根据城市名称查询和风天气Api的城市信息。
     * @param city 用户输入的城市名称，例如：北京
     * @return 和风天气原始城市查询响应
     * EG:北京
     *   ↓
     * 城市查询 API
     *   ↓
     * LocationID：101010100
     */
    public QWeatherCityLookupResponse lookupCity(String city) {

        log.info("Calling QWeather city lookup API: city={}", city);

        // qWeatherRestClient.get():创建一个 HTTP GET 请求
        return qWeatherRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        //添加接口路径
                        .path("/geo/v2/city/lookup")
                        .queryParam("location", city)
                        .queryParam("range", "cn")
                        .queryParam("lang", "zh")
                        //前面的路径和参数都设置好了，现在正式生成 URI 地址
                        .build()
                )
                //点击发送并获取响应
                .retrieve()
                //把和风天气返回的 JSON，转换成 QWeatherCityLookupResponse 对象
                .body(QWeatherCityLookupResponse.class);
    }

    /**
     * 根据和风天气的城市 ID 查询实时天气。
     * @param locationId 和风天气城市 ID，例如：101010100
     * @return 和风天气原始实时天气响应
     * EG:LocationID：101010100
     *   ↓
     * 实时天气 API
     *   ↓
     * 温度、湿度、风向、天气描述
     */
    public QWeatherNowResponse getCurrentWeather(String locationId) {

        log.info("Calling QWeather current weather API: locationId={}",
                locationId);

        return qWeatherRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v7/weather/now")
                        .queryParam("location", locationId)
                        .queryParam("lang", "zh")
                        .build()
                )
                .retrieve()
                .body(QWeatherNowResponse.class);
    }
}