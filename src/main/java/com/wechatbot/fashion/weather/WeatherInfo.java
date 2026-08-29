package com.wechatbot.fashion.weather;

/**
 * 提供给上层业务和 AI Tool 的稳定天气结果，不暴露第三方接口的原始 JSON。
 */
public record WeatherInfo(
        String province,
        String city,
        String weather,
        Integer temperatureCelsius,
        String windDirection,
        String windPower,
        Integer humidityPercent,
        String reportTime
) {
}
