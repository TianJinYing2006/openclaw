package com.wechatbot.fashion.tianxing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * 天行数据菜谱查询业务服务。
 * 搜索：GET /caipu/index?key={apiKey}&word={keyword}&num={num}
 * 详情：GET /caipu/cpinfo?key={apiKey}&id={id}
 */
@Service
public class RecipeService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public RecipeService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${tianxing.api-key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 搜索菜谱列表。
     *
     * @param keyword 菜名关键词
     * @param num     返回数量（1-10）
     * @return 菜谱列表
     */
    public List<RecipeInfo> searchRecipe(String keyword, int num) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        int count = Math.max(1, Math.min(num, 10));

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/caipu/index")
                        .queryParam("key", apiKey)
                        .queryParam("word", keyword.trim())
                        .queryParam("num", count)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(SearchResponse.class);

        if (response == null || response.code() != 200) {
            String msg = (response != null) ? response.msg() : "null";
            throw new IllegalStateException("菜谱查询失败: " + msg);
        }
        if (response.result() == null || response.result().isEmpty()) {
            return Collections.emptyList();
        }
        return response.result().stream()
                .map(item -> RecipeInfo.fromListItem(
                        item.id(),
                        item.cpName(),
                        item.typeName(),
                        item.texing()
                ))
                .toList();
    }

    /**
     * 获取菜谱详情。
     *
     * @param id 菜品 ID
     * @return 完整菜谱
     */
    public RecipeInfo getRecipeDetail(int id) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/caipu/cpinfo")
                        .queryParam("key", apiKey)
                        .queryParam("id", id)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(DetailResponse.class);

        if (response == null || response.code() != 200) {
            String msg = (response != null) ? response.msg() : "null";
            throw new IllegalStateException("菜谱详情查询失败: " + msg);
        }
        var result = response.result();
        if (result == null) {
            throw new IllegalStateException("菜谱详情未找到");
        }
        return new RecipeInfo(
                result.id(),
                result.cpName(),
                result.typeName(),
                result.texing(),
                result.yuanliao(),
                result.tiaoliao(),
                result.zuofa(),
                result.tishi()
        );
    }

    // ---------- 内部 JSON 映射 ----------

    private record SearchResponse(
            int code,
            String msg,
            List<SearchResultItem> result
    ) {
    }

    private record SearchResultItem(
            Integer id,
            @JsonProperty("cp_name") String cpName,
            @JsonProperty("type_name") String typeName,
            String texing
    ) {
    }

    private record DetailResponse(
            int code,
            String msg,
            DetailResultItem result
    ) {
    }

    private record DetailResultItem(
            Integer id,
            @JsonProperty("cp_name") String cpName,
            @JsonProperty("type_name") String typeName,
            String texing,
            String yuanliao,
            String tiaoliao,
            String zuofa,
            String tishi
    ) {
    }
}
