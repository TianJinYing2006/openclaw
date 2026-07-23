package com.example.ykdsummer.navigation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 高德路线规划结果。
 */
public record AmapRouteResult(
        String status,
        String info,
        @JsonProperty("route") Route route,
        String navigationLink
) {
    public record Route(
            @JsonProperty("origin") String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("paths") List<Path> paths
    ) {
        public String distance() {
            return paths != null && !paths.isEmpty() ? paths.get(0).distance() : "未知";
        }

        public String duration() {
            return paths != null && !paths.isEmpty() ? paths.get(0).duration() : "未知";
        }

        public List<Step> steps() {
            return paths != null && !paths.isEmpty() ? paths.get(0).steps() : List.of();
        }
    }

    public record Path(
            @JsonProperty("distance") String distance,
            @JsonProperty("duration") String duration,
            @JsonProperty("steps") List<Step> steps
    ) {
    }

    public record Step(
            @JsonProperty("instruction") String instruction,
            @JsonProperty("road") String road,
            @JsonProperty("distance") String distance,
            @JsonProperty("duration") String duration
    ) {
    }

    /**
     * 生成高德导航链接（网页版，可在微信中打开后跳转高德APP）。
     * 格式：https://uri.amap.com/navigation?from={起点经度},{起点纬度}&to={终点经度},{终点纬度}&mode={模式}&src=web
     */
    public static String buildNavigationLink(double originLon, double originLat,
                                             double destLon, double destLat,
                                             String destName, String mode) {
        String m = switch (mode) {
            case "walking" -> "walk";
            case "riding" -> "bike";
            case "transit" -> "bus";
            default -> "car"; // driving
        };
        return "https://uri.amap.com/navigation?from="
                + originLon + "," + originLat
                + "&to=" + destLon + "," + destLat
                + "&mode=" + m
                + "&src=ykd-bot&coordinate=gaode&callnative=1";
    }
}
