package com.wechatbot.fashion.tianxing;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * 天行数据旅游景区查询业务服务。
 * 接口地址：https://apis.tianapi.com/scenic/index?key={apiKey}
 * word / province / city 三个参数至少填一个。
 */
@Service
public class ScenicSpotService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final String apiKey;

    public ScenicSpotService(
            RestClient.Builder restClientBuilder,
            @Value("${tianxing.api-key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 按关键词搜索景点。
     */
    public List<ScenicSpotInfo> searchByKeyword(String keyword, int num) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }
        int count = Math.max(1, Math.min(num, 15));

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/scenic/index")
                        .queryParam("key", apiKey)
                        .queryParam("word", keyword.trim())
                        .queryParam("num", count)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        return parseResponse(response);
    }

    /**
     * 按城市搜索景点。
     */
    public List<ScenicSpotInfo> searchByCity(String city, int num) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }
        int count = Math.max(1, Math.min(num, 15));

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/scenic/index")
                        .queryParam("key", apiKey)
                        .queryParam("city", city.trim())
                        .queryParam("num", count)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        return parseResponse(response);
    }

    private List<ScenicSpotInfo> parseResponse(ApiResponse response) {
        if (response == null || response.code() != 200) {
            String msg = (response != null) ? response.msg() : "null";
            throw new IllegalStateException("景点查询失败: " + msg);
        }
        if (response.result() == null || response.result().isEmpty()) {
            return Collections.emptyList();
        }
        return response.result().stream()
                .map(item -> new ScenicSpotInfo(
                        item.name(),
                        item.province(),
                        item.city(),
                        item.content()
                ))
                .toList();
    }

    // ---------- 内部 JSON 映射 ----------

    private record ApiResponse(
            int code,
            String msg,
            List<ResultItem> result
    ) {
    }

    private record ResultItem(
            String name,
            String province,
            String city,
            String content
    ) {
    }
}
