package com.fourth.ykd.weather.controller;

import com.fourth.ykd.result.ApiResponse;
import com.fourth.ykd.weather.dto.WeatherInfoResponse;
import com.fourth.ykd.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * 按城市名称查询实时天气。
     * 示例：
     * GET /api/weather?city=北京
     */
    @GetMapping
    public ApiResponse<WeatherInfoResponse> getCurrentWeather(
            /*required = false，原因是：
                    如果参数缺失，Controller 不立即抛 Spring 默认异常；
                    city 会是 null；
                    交给 WeatherService；
                    WeatherService 抛出我们定义的 BusinessException(40001, "城市名称不能为空")；
                    最终得到统一 JSON 错误格式。*/
            @RequestParam(required = false) String city
    ) {
        log.info("Received weather query request: city={}", city);

        WeatherInfoResponse weatherInfo =
                weatherService.queryCurrentWeather(city);

        return ApiResponse.success(weatherInfo);
    }
}