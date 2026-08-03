package com.fourth.ykd.weather.infrastructure.qweather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**接收第三方原始 JSON
 * 描述和风天气城市查询接口的原始响应:
 *只负责接收第三方接口数据：Jackson把JSON自动转换成QWeatherCityLookupResponse对象
 * 最终转换出来相当于：
 * QWeatherCityLookupResponse response = ...;
 * response.getCode();                      // "200"
 * response.getLocation().get(0).getName(); // "郑州"
 * response.getLocation().get(0).getId();   // "101180101"
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QWeatherCityLookupResponse {

    /**
     * 和风天气接口响应码。
     * 例如：
     * 200：请求成功
     * 404：查询不到城市
     */
    private String code;

    /**
     * 查询到的城市列表。
     */
    private List<Location> location;

    /**
     * 城市信息。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {

        /**
         * 城市名称。
         */
        private String name;

        /**
         * 和风天气中的城市 ID。
         */
        private String id;
    }
}