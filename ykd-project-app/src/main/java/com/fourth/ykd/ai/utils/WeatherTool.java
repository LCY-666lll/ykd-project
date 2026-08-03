package com.fourth.ykd.ai.utils;

import com.fourth.ykd.weather.dto.WeatherForecastResponse;
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
            description = """
                    仅查询某城市或地区当前实时天气，包括温度、体感温度、湿度、降雨和风力。
                    用户本轮明确询问现在、当前、实时天气或今天此刻天气时必须重新调用，不得使用聊天记忆中的旧天气结果。
                    明天、后天、未来3天、每日最高最低温等天气预报请求不得调用本工具，应调用 query_weather_forecast。
                    新闻、时事、政策、经济和科技动态问题不得调用本工具。
                    """
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


    @Tool(
            name = "query_weather_forecast",
            description = """
                    查询某城市或地区今天、明天和后天的每日天气预报。
                    用户询问今天至后天的最高最低温、白天夜间天气、每日预报或未来降水时调用。
                    用户本轮明确查询天气预报时必须重新调用，不得使用聊天记忆中的旧预报结果。
                    当前温度、体感温度等实时天气请求不得调用本工具，应调用 query_current_weather。
                    """
    )
    public WeatherForecastResponse queryWeatherForecast(
            @ToolParam(description = "要查询未来3天天气预报的城市名称，例如北京、上海、杭州", required = true)
            String city
    ) {
        String normalizedCity = city == null ? null : city.trim();
        log.info("[AI][TOOL][WEATHER_FORECAST][START] city={}", normalizedCity);
        try {
            WeatherForecastResponse result = weatherService.queryThreeDayForecast(normalizedCity);
            log.info("[AI][TOOL][WEATHER_FORECAST][SUCCESS] city={}, days={}",
                    result.getCity(), result.getDaily().size());
            return result;
        } catch (RuntimeException exception) {
            log.warn("[AI][TOOL][WEATHER_FORECAST][FAILED] city={}, reason={}",
                    normalizedCity, exception.getMessage());
            throw exception;
        }
    }
}
