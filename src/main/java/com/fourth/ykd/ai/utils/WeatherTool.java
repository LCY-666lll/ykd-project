package com.fourth.ykd.ai.utils;

import com.fourth.ykd.weather.dto.WeatherInfoResponse;
import com.fourth.ykd.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 复用项目中和风天气,提供给大模型调用的实时天气查询工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool{

    private final WeatherService weatherService;

    @Tool(
            name = "query_current_weather",
            description = "查询用户要求城市的实时天气，当用户询问某个城市当前天气、气温、体感温度、湿度、风向或风速时调用。"
    )
    public WeatherInfoResponse queryCurrentWeather(
            @ToolParam(description = "要查询的城市名称，例如北京、上海、杭州", required = true) String city
    ) {
        String normalizedCity = city == null ? null : city.trim();

        log.info("[AI][TOOL][WEATHER][START] 开始调用天气工具，city={}",
                 normalizedCity);

        try {
            WeatherInfoResponse result =
                    weatherService.queryCurrentWeather(normalizedCity);

            log.info(
                    "[AI][TOOL][WEATHER][SUCCESS] 天气工具调用成功，city={}, temp={}, text={}, humidity={}",
                    result.getCity(),
                    result.getTemp(),
                    result.getText(),
                    result.getHumidity()
            );

            return result;
        } catch (RuntimeException exception) {
            log.warn(
                    "[AI][TOOL][WEATHER][FAILED] 天气工具调用失败，city={}, reason={}",
                    normalizedCity,
                    exception.getMessage()
            );
            throw exception;
        }
    }
}