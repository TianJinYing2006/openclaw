package com.wechatbot.fashion.navigation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 高德地理编码结果。
 */
public record AmapGeoResult(
        String status,
        String info,
        @JsonProperty("geocodes") List<Geocode> geocodes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Geocode(
            String formatted_address,
            String citycode,
            String adcode,
            @JsonProperty("location") String location
    ) {
        public double longitude() {
            String[] parts = location.split(",");
            return Double.parseDouble(parts[0]);
        }

        public double latitude() {
            String[] parts = location.split(",");
            return Double.parseDouble(parts[1]);
        }
    }
}
