package com.wechatbot.fashion.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 天气业务服务。它只负责参数校验和调用天气数据源，不包含任何大模型提示词。
 */
@Service
@ConditionalOnProperty(prefix = "app.weather", name = "provider",
        havingValue = "uapis", matchIfMissing = true)
public class WeatherService implements WeatherProvider {

    private static final String BASE_URL = "https://uapis.cn";

    private final RestClient restClient;

    public WeatherService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public WeatherInfo getCurrentWeather(String city) {
        String normalizedCity = normalizeCity(city);
        WeatherApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/misc/weather")
                        .queryParam("city", normalizedCity)
                        .queryParam("lang", "zh")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(WeatherApiResponse.class);

        if (response == null || response.city() == null || response.city().isBlank()) {
            throw new IllegalStateException("天气服务没有返回有效数据");
        }
        return new WeatherInfo(
                response.province(),
                response.city(),
                response.weather(),
                response.temperature(),
                response.windDirection(),
                response.windPower(),
                response.humidity(),
                response.reportTime()
        );
    }

    private static String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
        String normalized = city.strip();
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("城市名称过长");
        }
        return normalized;
    }

    private record WeatherApiResponse(
            String province,
            String city,
            String weather,
            Integer temperature,
            @JsonProperty("wind_direction") String windDirection,
            @JsonProperty("wind_power") String windPower,
            Integer humidity,
            @JsonProperty("report_time") String reportTime
    ) {
    }
}
