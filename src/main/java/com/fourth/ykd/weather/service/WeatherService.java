package com.fourth.ykd.weather.service;

import com.fourth.ykd.weather.dto.WeatherInfoResponse;

public interface WeatherService {

    WeatherInfoResponse queryCurrentWeather(String city);
}