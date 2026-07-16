package com.example.ykdsummer.service;

import com.example.ykdsummer.exception.InvalidCommandException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WeatherService {

    private final RestClient restClient = RestClient.create("https://uapis.cn");

    public String queryWeather(String city) {
        if (city == null || city.trim().isEmpty()) {
            throw new InvalidCommandException("错误：请输入城市名称");
        }

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/misc/weather")
                        .queryParam("city", city.trim())
                        .queryParam("lang", "zh")
                        .build())
                .retrieve()
                .body(String.class);
    }
}
