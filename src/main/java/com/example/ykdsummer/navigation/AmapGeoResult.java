package com.example.ykdsummer.navigation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 高德地理编码结果。
 */
public record AmapGeoResult(
        String status,
        String info,
        @JsonProperty("geocodes") List<Geocode> geocodes
) {
    public record Geocode(
            String formatted_address,
            String country,
            String province,
            String city,
            String citycode,
            String adcode,
            String district,
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
