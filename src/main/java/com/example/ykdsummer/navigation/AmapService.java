package com.example.ykdsummer.navigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 高德地图 Web API 服务。负责地理编码和路线规划。
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

    /**
     * 地理编码：将地址文本转换为经纬度坐标。
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
     * 路线规划：根据起点和终点坐标规划出行路线。
     *
     * @param origin      起点坐标，格式 "经度,纬度"
     * @param destination 终点坐标，格式 "经度,纬度"
     * @param mode        出行方式：driving/walking/riding/transit
     * @param destName    终点名称，用于生成导航链接
     * @return 路线规划结果（含导航链接）
     */
    public AmapRouteResult routePlan(String origin, String destination, String mode, String destName) {
        log.info("Amap routePlan: origin={}, destination={}, mode={}", origin, destination, mode);
        AmapRouteResult result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v3/direction/" + mode)
                        .queryParam("key", apiKey)
                        .queryParam("origin", origin)
                        .queryParam("destination", destination)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AmapRouteResult.class);

        // 生成高德导航链接（网页版）
        String[] originParts = origin.split(",");
        double originLon = Double.parseDouble(originParts[0]);
        double originLat = Double.parseDouble(originParts[1]);
        String[] destParts = destination.split(",");
        double destLon = Double.parseDouble(destParts[0]);
        double destLat = Double.parseDouble(destParts[1]);
        String navLink = AmapRouteResult.buildNavigationLink(originLon, originLat, destLon, destLat, destName, mode);

        return new AmapRouteResult(result.status(), result.info(), result.route(), navLink);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
    }
}
