package com.fourth.ykd.weather.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fourth.ykd.weather.dto.WeatherForecastResponse;
import com.fourth.ykd.weather.infrastructure.qweather.QWeatherClient;
import com.fourth.ykd.weather.infrastructure.qweather.WeatherProperties;
import com.fourth.ykd.weather.infrastructure.qweather.dto.QWeatherCityLookupResponse;
import com.fourth.ykd.weather.infrastructure.qweather.dto.QWeatherForecastResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证3日天气预报复用现有城市查询和和风配置。 */
class WeatherServiceImplTest {

    @Test
    void shouldConvertThreeDayForecast() {
        QWeatherClient client = mock(QWeatherClient.class);
        WeatherProperties properties = new WeatherProperties();
        properties.setApiKey("configured");
        WeatherServiceImpl service = new WeatherServiceImpl(client, properties);

        QWeatherCityLookupResponse.Location location = new QWeatherCityLookupResponse.Location();
        location.setName("北京");
        location.setId("101010100");
        QWeatherCityLookupResponse cityResponse = new QWeatherCityLookupResponse();
        cityResponse.setCode("200");
        cityResponse.setLocation(List.of(location));

        QWeatherForecastResponse.Daily firstDay = new QWeatherForecastResponse.Daily();
        firstDay.setFxDate("2026-07-26");
        firstDay.setTempMin("24");
        firstDay.setTempMax("31");
        firstDay.setTextDay("多云");
        firstDay.setTextNight("多云");
        QWeatherForecastResponse forecastResponse = new QWeatherForecastResponse();
        forecastResponse.setCode("200");
        forecastResponse.setUpdateTime("2026-07-26T12:00+08:00");
        forecastResponse.setDaily(List.of(firstDay));
        when(client.lookupCity("北京")).thenReturn(cityResponse);
        when(client.getThreeDayForecast("101010100")).thenReturn(forecastResponse);

        WeatherForecastResponse result = service.queryThreeDayForecast(" 北京 ");

        assertThat(result.getCity()).isEqualTo("北京");
        assertThat(result.getDaily()).hasSize(1);
        assertThat(result.getDaily().getFirst().getDate()).isEqualTo("2026-07-26");
        assertThat(result.getDaily().getFirst().getTempMin()).isEqualTo("24");
        assertThat(result.getDaily().getFirst().getTempMax()).isEqualTo("31");
        verify(client).getThreeDayForecast("101010100");
    }
}
