package com.fourth.ykd.weather.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 定义我们接口返回给调用者的数据
 * 不直接依赖和风天气的原始返回格式，
 * 只保留当前业务需要展示的字段。
 * 和风城市查询响应
 *       +
 * 和风实时天气响应
 *       ↓ WeatherService 组合
 * WeatherInfoResponse
 *       ↓ Controller
 * ApiResponse<WeatherInfoResponse>
 *       ↓
 * 调用者收到 JSON
 */
@Getter
@Setter
@NoArgsConstructor
public class WeatherInfoResponse {

    /**
     * 用户实际查询到的城市名称。
     */
    private String city;

    /**
     * 和风天气接口数据更新时间。
     */
    private String updateTime;

    /**
     * 实况观测时间。
     */
    private String obsTime;

    /**
     * 当前温度，单位：摄氏度。
     */
    private String temp;

    /**
     * 体感温度，单位：摄氏度。
     */
    private String feelsLike;

    /**
     * 天气描述，例如：晴、多云、小雨。
     */
    private String text;

    /**
     * 风向。
     */
    private String windDir;

    /**
     * 风速，单位：公里/小时。
     */
    private String windSpeed;

    /**
     * 相对湿度，单位：百分比。
     */
    private String humidity;
}