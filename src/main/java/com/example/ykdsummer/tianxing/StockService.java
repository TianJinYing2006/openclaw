package com.example.ykdsummer.tianxing;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票行情业务服务。调用天行数据 finance 接口。
 */
@Service
public class StockService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final String apiKey;

    public StockService(
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
     * 查询股票行情。
     *
     * @param codes 股票代码，例如 hk00700,sh000001,sz000001
     * @return 行情列表
     */
    public List<StockInfo> queryStocks(List<String> codes) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }
        if (codes == null || codes.isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }

        String codeParam = String.join(",", codes);
        int list = codes.size() > 1 ? 1 : 0;
        String url = "/finance/index?key=" + apiKey
                + "&code=" + java.net.URLEncoder.encode(codeParam, java.nio.charset.StandardCharsets.UTF_8)
                + "&list=" + list;

        ApiResponse response = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.code() != 200) {
            throw new IllegalStateException("股票行情查询失败: "
                    + (response != null ? response.msg() : "无响应"));
        }
        if (response.result() == null || response.result().data().isEmpty()) {
            throw new IllegalStateException("股票行情返回数据为空");
        }

        List<StockInfo> stocks = new ArrayList<>();
        for (var entry : response.result().data().entrySet()) {
            String code = entry.getKey();
            String rawData = entry.getValue();
            if (rawData != null && !rawData.isBlank()) {
                stocks.add(StockInfo.fromApiData(code, rawData));
            }
        }
        return stocks;
    }

    @SuppressWarnings("unused")
    private record ApiResponse(
            int code,
            String msg,
            Result result
    ) {
    }

    @SuppressWarnings("unused")
    private record Result(
            @JsonAnySetter Map<String, String> data
    ) {
        public Result {
            // 显式初始化避免不可变集合
            if (data == null) {
                data = new LinkedHashMap<>();
            }
        }
    }
}
