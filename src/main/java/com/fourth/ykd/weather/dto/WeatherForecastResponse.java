package com.fourth.ykd.weather.dto;

import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 提供给业务层和AI工具使用的3日天气预报。 */
@Getter
@Setter
@NoArgsConstructor
public class WeatherForecastResponse {

    private String city;
    private String updateTime;
    private List<DailyForecast> daily;

    /** 单日天气预报。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class DailyForecast {

        private String date;
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
