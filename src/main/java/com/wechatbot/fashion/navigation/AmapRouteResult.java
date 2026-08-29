package com.wechatbot.fashion.navigation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 高德路线规划结果（v5 API）。
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
            @JsonProperty("paths") List<Path> paths,
            @JsonProperty("transits") List<Transit> transits
    ) {
        /**
         * 获取距离。公交模式优先从 transits 取，其他模式从 paths 取。
         */
        public String distance() {
            if (transits != null && !transits.isEmpty()) {
                return transits.get(0).distance();
            }
            return paths != null && !paths.isEmpty() ? paths.get(0).distance() : "未知";
        }

        /**
         * 获取耗时。公交模式优先从 transits 取，其他模式从 paths 取。
         */
        public String duration() {
            if (transits != null && !transits.isEmpty()) {
                return transits.get(0).duration();
            }
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
            @JsonProperty("road_name") String roadName,
            @JsonProperty("step_distance") String stepDistance,
            @JsonProperty("orientation") String orientation
    ) {
    }

    /**
     * 公交换乘方案（v5 transit 返回 transits[]）。
     */
    public record Transit(
            @JsonProperty("distance") String distance,
            @JsonProperty("duration") String duration,
            @JsonProperty("nightflag") String nightflag,
            @JsonProperty("segments") List<Object> segments
    ) {
    }

    /**
     * 生成高德导航链接（网页版，可在微信中打开后跳转高德APP）。
     */
    public static String buildNavigationLink(double originLon, double originLat,
                                             double destLon, double destLat,
                                             String destName, String mode) {
        String m = switch (mode) {
            case "walking" -> "walk";
            case "riding" -> "bike";
            case "transit" -> "bus";
            default -> "car";
        };
        return "https://uri.amap.com/navigation?from="
                + originLon + "," + originLat
                + "&to=" + destLon + "," + destLat
                + "&mode=" + m
                + "&src=ykd-bot&coordinate=gaode&callnative=1";
    }
}
