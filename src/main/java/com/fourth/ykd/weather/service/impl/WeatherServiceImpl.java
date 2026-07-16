package com.fourth.ykd.weather.service.impl;

import com.fourth.ykd.client.QWeatherClient;
import com.fourth.ykd.client.dto.QWeatherCityLookupResponse;
import com.fourth.ykd.client.dto.QWeatherNowResponse;
import com.fourth.ykd.config.WeatherProperties;
import com.fourth.ykd.exception.BusinessException;
import com.fourth.ykd.weather.dto.WeatherInfoResponse;
import com.fourth.ykd.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherServiceImpl implements WeatherService {

    private final QWeatherClient qWeatherClient;

    private final WeatherProperties weatherProperties;

    @Override
    public WeatherInfoResponse queryCurrentWeather(String city) {
        if (!StringUtils.hasText(city)) {
            throw new BusinessException(40001, "城市名称不能为空");
        }

        if (!StringUtils.hasText(weatherProperties.getApiKey())) {
            throw new BusinessException(50001, "天气服务 API Key 未配置");
        }

        String cityName = city.trim();
        long startTime = System.currentTimeMillis();

        QWeatherCityLookupResponse cityResponse =
                qWeatherClient.lookupCity(cityName);

        if (cityResponse == null
                || !"200".equals(cityResponse.getCode())
                || cityResponse.getLocation() == null
                || cityResponse.getLocation().isEmpty()) {
            log.warn("City lookup failed: city={}, qWeatherCode={}",
                    cityName,
                    cityResponse == null ? null : cityResponse.getCode());
            throw new BusinessException(40002, "未查询到城市：" + cityName);
        }

        QWeatherCityLookupResponse.Location location =
                cityResponse.getLocation().stream()
                        .filter(item -> cityName.equals(item.getName()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(
                                40002,
                                "未查询到城市：" + cityName
                        ));

        QWeatherNowResponse weatherResponse =
                qWeatherClient.getCurrentWeather(location.getId());

        if (weatherResponse == null
                || !"200".equals(weatherResponse.getCode())
                || weatherResponse.getNow() == null) {
            log.warn("Current weather lookup failed: city={}, locationId={}, qWeatherCode={}",
                    cityName,
                    location.getId(),
                    weatherResponse == null ? null : weatherResponse.getCode());
            throw new BusinessException(50002, "天气服务查询失败，请稍后重试");
        }

        WeatherInfoResponse response = convertToWeatherInfo(
                location,
                weatherResponse
        );

        log.info("Weather query succeeded: city={}, locationId={}, temp={}, text={}, elapsedMs={}",
                response.getCity(),
                location.getId(),
                response.getTemp(),
                response.getText(),
                System.currentTimeMillis() - startTime);

        return response;
    }

    private WeatherInfoResponse convertToWeatherInfo(
            QWeatherCityLookupResponse.Location location,
            QWeatherNowResponse weatherResponse
    ) {
        QWeatherNowResponse.Now now = weatherResponse.getNow();

        WeatherInfoResponse response = new WeatherInfoResponse();
        response.setCity(location.getName());
        response.setUpdateTime(weatherResponse.getUpdateTime());
        response.setObsTime(now.getObsTime());
        response.setTemp(now.getTemp());
        response.setFeelsLike(now.getFeelsLike());
        response.setText(now.getText());
        response.setWindDir(now.getWindDir());
        response.setWindSpeed(now.getWindSpeed());
        response.setHumidity(now.getHumidity());
        return response;
    }
}