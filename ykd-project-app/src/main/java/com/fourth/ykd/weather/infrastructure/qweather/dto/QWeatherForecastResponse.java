package com.fourth.ykd.weather.infrastructure.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 描述和风天气3日天气预报接口的原始响应。 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QWeatherForecastResponse {

    private String code;
    private String updateTime;
    private List<Daily> daily;

    /** 每日天气预报。 */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Daily {

        private String fxDate;
        private String tempMax;
        private String tempMin;
        private String textDay;
        private String textNight;
        private String windDirDay;
        private String windScaleDay;
        private String windDirNight;
        private String windScaleNight;
        private String humidity;
        private String precip;
    }
}
