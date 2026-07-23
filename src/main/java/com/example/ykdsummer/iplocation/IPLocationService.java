package com.example.ykdsummer.iplocation;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * IP 地理位置查询服务 — 使用 ip-api.com 免费接口。
 *
 * <p>端点：http://ip-api.com/json/{query}?lang=zh-CN</p>
 * <p>限制：每分钟 45 次 / IP</p>
 * <p>注意：免费版不支持 HTTPS，使用 HTTP</p>
 */
@Service
public class IPLocationService {

    private static final Logger log = LoggerFactory.getLogger(IPLocationService.class);
    private static final String BASE_URL = "http://ip-api.com";

    private final RestClient restClient;

    public IPLocationService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
    }

    /**
     * 查询指定 IP 的地理位置信息。
     *
     * @param ip IPv4/IPv6 地址或域名。传 null 或空字符串则查询当前 IP。
     * @return 地理位置信息
     */
    public IPLocationInfo queryLocation(String ip) {
        String queryPath = (ip == null || ip.isBlank()) ? "json" : "json/" + ip.strip();

        log.info("Querying IP location: {}", queryPath);

        IPApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/" + queryPath)
                        .queryParam("lang", "zh-CN")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(IPApiResponse.class);

        if (response == null) {
            throw new IllegalStateException("ip-api.com 返回空响应");
        }

        return toDomain(response);
    }

    private static IPLocationInfo toDomain(IPApiResponse resp) {
        return new IPLocationInfo(
                resp.query() != null ? resp.query() : "",
                resp.status() != null ? resp.status() : "fail",
                resp.message(),
                resp.country(),
                resp.countryCode(),
                resp.region(),
                resp.regionName(),
                resp.city(),
                resp.zip(),
                resp.lat(),
                resp.lon(),
                resp.timezone(),
                resp.isp(),
                resp.org(),
                resp.asn(),
                resp.mobile(),
                resp.proxy(),
                resp.hosting()
        );
    }

    /** ip-api.com JSON 响应原始映射 */
    @SuppressWarnings("unused")
    private record IPApiResponse(
            String status,
            String message,
            String country,
            String countryCode,
            String region,
            String regionName,
            String city,
            String zip,
            @JsonProperty("lat") double lat,
            @JsonProperty("lon") double lon,
            String timezone,
            String isp,
            String org,
            @JsonProperty("as") String asn,
            boolean mobile,
            boolean proxy,
            boolean hosting,
            String query
    ) {}
}
