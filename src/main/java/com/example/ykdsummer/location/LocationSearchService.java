package com.example.ykdsummer.location;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class LocationSearchService {

    private static final String BASE_URL = "https://apis.map.qq.com";

    private final RestClient restClient;
    private final String apiKey;

    public LocationSearchService(
            RestClient.Builder restClientBuilder,
            @Value("${tencent.map.key:OM6BZ-ULWEL-YNAPE-MFJMN-SIWMS-YLF7E}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    public LocationSearchInfo search(String keyword, String city) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }

        String boundary = "region(" + city.strip() + ",0)";
        return doSearch(keyword, boundary);
    }

    public LocationSearchInfo searchNearby(String keyword, double lat, double lng, int radiusMeters) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        if (radiusMeters <= 0) {
            radiusMeters = 1000;
        }

        String boundary = "nearby(" + lat + "," + lng + "," + radiusMeters + ")";
        return doSearch(keyword, boundary);
    }

    private LocationSearchInfo doSearch(String keyword, String boundary) {
        ApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ws/place/v1/search")
                        .queryParam("key", apiKey)
                        .queryParam("keyword", keyword.strip())
                        .queryParam("boundary", boundary)
                        .queryParam("page_size", 10)
                        .queryParam("output", "json")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.status() != 0) {
            String msg = response == null ? "无响应" : response.message();
            throw new IllegalStateException("地点搜索失败: " + msg);
        }

        List<LocationSearchInfo.Poi> pois = response.data() == null ? List.of() : response.data().stream()
                .map(d -> new LocationSearchInfo.Poi(
                        d.id(),
                        d.title(),
                        d.address(),
                        d.tel(),
                        d.category(),
                        d.location() == null ? 0 : d.location().lat(),
                        d.location() == null ? 0 : d.location().lng(),
                        d.distance(),
                        d.adInfo() == null ? "" : d.adInfo().province(),
                        d.adInfo() == null ? "" : d.adInfo().city(),
                        d.adInfo() == null ? "" : d.adInfo().district()
                ))
                .toList();

        return new LocationSearchInfo(response.count(), pois);
    }

    private record ApiResponse(
            int status,
            String message,
            int count,
            List<PoiData> data
    ) {
    }

    private record PoiData(
            String id,
            String title,
            String address,
            String tel,
            String category,
            Location location,
            @JsonProperty("_distance") int distance,
            @JsonProperty("ad_info") AdInfo adInfo
    ) {
    }

    private record Location(double lat, double lng) {
    }

    private record AdInfo(
            String province,
            String city,
            String district
    ) {
    }

    /**
     * 地理编码：将地址或地名转换为经纬度坐标。
     */
    public GeocodeResult geocode(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("地址不能为空");
        }

        GeocodeResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/ws/geocoder/v1/")
                        .queryParam("key", apiKey)
                        .queryParam("address", address.strip())
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(GeocodeResponse.class);

        if (response == null || response.status() != 0) {
            String msg = response == null ? "无响应" : response.message();
            throw new IllegalStateException("地理编码失败: " + msg);
        }

        if (response.result() == null || response.result().location() == null) {
            throw new IllegalStateException("无法找到地址: " + address);
        }

        GeocodeLocation loc = response.result().location();
        String formattedAddr = response.result().formattedAddresses() != null
                ? response.result().formattedAddresses().recommend()
                : response.result().address();
        return new GeocodeResult(
                formattedAddr != null ? formattedAddr : address,
                loc.lat(),
                loc.lng()
        );
    }

    public record GeocodeResult(String address, double lat, double lng) {}

    private record GeocodeResponse(int status, String message, GeocodeResultData result) {}

    private record GeocodeResultData(String address, GeocodeLocation location,
                                      @JsonProperty("formatted_addresses") FormattedAddresses formattedAddresses) {}

    private record GeocodeLocation(double lat, double lng) {}

    private record FormattedAddresses(String recommend) {}
}
