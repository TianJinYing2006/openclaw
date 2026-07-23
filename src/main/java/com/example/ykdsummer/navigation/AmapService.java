package com.example.ykdsummer.navigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 高德地图 Web API 服务。负责地理编码和路线规划（v5 API）。
 */
@Service
public class AmapService {

    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private static final String BASE_URL = "https://restapi.amap.com";

    private final RestClient restClient;
    private final String apiKey;

    public AmapService(
            RestClient.Builder restClientBuilder,
            @Value("${amap.api-key:not-configured}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    AmapService(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    /**
     * 地理编码：将地址文本转换为经纬度坐标。
     * 高德当前地理编码接口仍为 v3，响应自带 citycode，供公交路线规划使用。
     *
     * @param address 地址文本，如"杭州市西湖区"
     * @param city    可选，城市名称，用于提高精度
     * @return 地理编码结果
     */
    public AmapGeoResult geocode(String address, String city) {
        log.info("Amap geocode: address={}, city={}", address, city);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/geocode/geo")
                        .queryParam("key", apiKey)
                        .queryParam("address", address)
                        .queryParam("output", "JSON")
                        .queryParamIfPresent("city", java.util.Optional.ofNullable(city))
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AmapGeoResult.class);
    }

    /**
     * 路线规划（v5 API）：根据起点和终点坐标规划出行路线。
     *
     * @param origin      起点坐标，格式 "经度,纬度"
     * @param destination 终点坐标，格式 "经度,纬度"
     * @param mode        出行方式：driving/walking/riding/transit
     * @param destName    终点名称，用于生成导航链接
     * @param originCity  起点城市编码（citycode），公交模式必填
     * @param destCity    终点城市编码（citycode），跨城公交必填
     * @return 路线规划结果（含导航链接）
     */
    public AmapRouteResult routePlan(String origin, String destination, String mode,
                                     String destName, String originCity, String destCity) {
        log.info("Amap routePlan: origin={}, destination={}, mode={}, originCity={}, destCity={}",
                origin, destination, mode, originCity, destCity);

        String path = resolveV5Path(mode);

        AmapRouteResult result = restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path(path)
                            .queryParam("key", apiKey)
                            .queryParam("origin", origin)
                            .queryParam("destination", destination);
                    // 非公交模式：添加 show_fields=cost 获取 duration
                    if (!"transit".equals(mode)) {
                        builder.queryParam("show_fields", "cost");
                    }
                    // 公交模式：添加城市参数
                    if ("transit".equals(mode)) {
                        if (originCity != null && !originCity.isBlank()) {
                            builder.queryParam("city1", originCity);
                        }
                        if (destCity != null && !destCity.isBlank()) {
                            builder.queryParam("city2", destCity);
                        }
                    }
                    return builder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AmapRouteResult.class);

        if (result == null || !"1".equals(result.status())) {
            String info = result != null ? result.info() : "null response";
            log.warn("Amap routePlan failed: status={}, info={}", result != null ? result.status() : "null", info);
            return new AmapRouteResult("0", info, null, null);
        }

        // 生成高德导航链接（网页版）
        String navLink = null;
        try {
            String[] originParts = origin.split(",");
            double originLon = Double.parseDouble(originParts[0]);
            double originLat = Double.parseDouble(originParts[1]);
            String[] destParts = destination.split(",");
            double destLon = Double.parseDouble(destParts[0]);
            double destLat = Double.parseDouble(destParts[1]);
            navLink = AmapRouteResult.buildNavigationLink(originLon, originLat, destLon, destLat, destName, mode);
        } catch (Exception e) {
            log.warn("Failed to build navigation link", e);
        }

        return new AmapRouteResult(result.status(), result.info(), result.route(), navLink);
    }

    private String resolveV5Path(String mode) {
        return switch (mode) {
            case "driving" -> "/v5/direction/driving";
            case "walking" -> "/v5/direction/walking";
            case "riding" -> "/v5/direction/bicycling";
            case "transit" -> "/v5/direction/transit/integrated";
            default -> "/v5/direction/driving";
        };
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }
}
