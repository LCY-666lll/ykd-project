package com.fourth.ykd.weather.infrastructure.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**接收第三方原始 JSON
 * 描述和风天气实时天气接口的原始响应。
 * 只负责接收第三方接口数据：
 * 不写业务逻辑；
 * 不包装项目统一返回结果；
 * 不进行成功失败判断。
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QWeatherNowResponse {

    /**
     * 和风天气接口响应码。
     * 例如：
     * 200：请求成功
     */
    private String code;

    /**
     * 和风天气接口数据更新时间。
     */
    private String updateTime;

    /**
     * 当前实时天气数据。
     */
    private Now now;

    /**
     * 实时天气详情。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Now {

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
         * 天气文字描述，例如：晴、多云、小雨。
         */
        private String text;

        /**
         * 风向，例如：东北风。
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
}