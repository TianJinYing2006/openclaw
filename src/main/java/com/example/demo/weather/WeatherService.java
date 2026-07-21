package com.example.demo.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 天气服务 — 接入心知天气 v3 每日预报 API
 * https://seniverse.yuque.com/docs/share/2547b202-6729-49f9-a011-3522747ce78b
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final String DAILY_URL = "https://api.seniverse.com/v3/weather/daily.json";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${weather.api.private-key:}")
    private String apiKey;

    public WeatherService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 查询天气
     * @param city   城市名（中文如"北京"，英文如"London"，多词如"New York"）
     * @param days   预报天数（0~3，传0默认3天）
     * @return 格式化的天气信息文本
     */
    public String queryWeather(String city, int days) {
        // 参数校验
        if (city == null || city.isBlank()) {
            return "❌ 城市名不能为空，用法: /weather <城市名> [天数]";
        }
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isBlank()) {
            return "❌ API Key 未配置，请在 application.properties 中设置 weather.api.private-key";
        }

        if (days <= 0 || days > 3) {
            days = 3;
        }

        long startNanos = System.nanoTime();
        String encodedCity = URLEncoder.encode(city.trim(), StandardCharsets.UTF_8);
        String url = "%s?key=%s&location=%s&language=zh-Hans&unit=c&days=%d"
                .formatted(DAILY_URL, key, encodedCity, days);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            int statusCode = response.statusCode();
            log.info("天气API请求: city={}, status={}, elapsed={}ms, url={}", city, statusCode, elapsedMs, url);

            if (statusCode == 401) {
                return "❌ API Key 无效或未授权，请检查 weather.api.private-key 配置";
            }
            if (statusCode != 200) {
                // 尝试从非 200 响应体中解析错误信息
                String errorMsg = tryExtractApiError(response.body());
                if (errorMsg != null) {
                    return "❌ " + errorMsg;
                }
                return "❌ 天气 API 返回异常状态码: " + statusCode;
            }

            return parseWeatherResponse(response.body(), days);
        } catch (java.net.UnknownHostException e) {
            log.error("天气API DNS解析失败: {}", e.getMessage());
            return "❌ 网络不可用，无法解析域名 api.seniverse.com，请检查网络连接";
        } catch (java.net.ConnectException e) {
            log.error("天气API连接失败: {}", e.getMessage());
            return "❌ 无法连接到天气服务，请检查网络或防火墙设置";
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("天气API请求超时: {}", e.getMessage());
            return "❌ 天气 API 请求超时，请稍后重试";
        } catch (Exception e) {
            log.error("天气API请求异常", e);
            return "❌ 查询天气时出错: " + e.getMessage();
        }
    }

    /**
     * 解析天气 API JSON 响应，格式化输出
     */
    private String parseWeatherResponse(String json, int days) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        // 检查 API 错误码
        JsonNode statusNode = root.get("status_code");
        if (statusNode != null) {
            String code = statusNode.asText();
            if ("AP010010".equals(code)) {
                return "❌ 城市不存在，请检查城市名是否正确";
            }
            if ("AP010006".equals(code)) {
                return "❌ 该城市数据不可用（免费版仅支持国内城市）";
            }
            if (!"OK".equalsIgnoreCase(code)) {
                JsonNode status = root.get("status");
                String detail = status != null ? status.asText() : code;
                return "❌ " + detail;
            }
        }

        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "❌ 未找到该城市的天气数据";
        }

        JsonNode result = results.get(0);
        JsonNode location = result.get("location");
        String cityName = location != null ? location.get("name").asText() : "未知城市";

        JsonNode dailyArray = result.get("daily");
        if (dailyArray == null || !dailyArray.isArray() || dailyArray.isEmpty()) {
            return "❌ 未获取到天气预报数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== ").append(cityName).append(" 天气预报 =====\n");

        int count = Math.min(dailyArray.size(), days);
        for (int i = 0; i < count; i++) {
            JsonNode day = dailyArray.get(i);
            String date = day.get("date").asText();
            String textDay = day.get("text_day").asText();
            String textNight = day.get("text_night").asText();
            String high = day.get("high").asText();
            String low = day.get("low").asText();
            String windDirection = day.get("wind_direction").asText();
            String windSpeed = day.get("wind_speed").asText();
            String humidity = day.get("humidity").asText();

            sb.append(String.format("  %s  %s\n", date, textDay));
            sb.append(String.format("  ├ 温度: %s°C ~ %s°C\n", low, high));
            sb.append(String.format("  ├ 夜间: %s\n", textNight));
            sb.append(String.format("  ├ 风向: %s  风速: %skm/h\n", windDirection, windSpeed));
            sb.append(String.format("  └ 湿度: %s%%\n", humidity));
        }

        sb.append("========================");
        return sb.toString();
    }

    /**
     * 尝试从 API 错误响应中提取错误消息
     */
    private String tryExtractApiError(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode statusCode = root.get("status_code");
            JsonNode status = root.get("status");
            if (statusCode != null && status != null) {
                String code = statusCode.asText();
                String msg = status.asText();
                if ("AP010010".equals(code)) {
                    return "城市不存在，请检查城市名是否正确";
                }
                if ("AP010006".equals(code)) {
                    return "该城市数据不可用（免费版仅支持国内城市）";
                }
                return msg + " (" + code + ")";
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
