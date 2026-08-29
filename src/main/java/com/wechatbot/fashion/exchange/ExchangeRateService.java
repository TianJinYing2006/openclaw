package com.wechatbot.fashion.exchange;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 汇率转换业务服务。调用 ExchangeRate-API v6 的 Pair 转换端点。
 */
@Service
public class ExchangeRateService {

    private static final String BASE_URL = "https://v6.exchangerate-api.com/v6";

    private final RestClient restClient;
    private final String apiKey;

    public ExchangeRateService(
            RestClient.Builder restClientBuilder,
            @Value("${exchangerate.api-key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.apiKey = apiKey;
    }

    /**
     * 转换指定金额从 base 货币到 target 货币。
     *
     * @param base   源币种，例如 USD
     * @param target 目标币种，例如 CNY
     * @param amount 金额
     * @return 转换结果
     */
    public ExchangeRateInfo convertCurrency(String base, String target, double amount) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallbackPair(base, target, amount);
        }

        String url = String.format("/%s/pair/%s/%s/%.0f", apiKey, base.toUpperCase(), target.toUpperCase(), amount);
        PairResponse response = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(PairResponse.class);

        if (response == null || !"success".equals(response.result)) {
            throw new IllegalStateException("汇率服务返回错误: "
                    + (response != null ? response.errorType : "无响应"));
        }

        return new ExchangeRateInfo(
                response.baseCode,
                response.targetCode,
                response.conversionRate,
                response.conversionResult,
                response.timeLastUpdateUtc
        );
    }

    /**
     * 当 API Key 未配置时，降级使用 open.er-api.com 的免费端点（每日一次更新）。
     */
    private ExchangeRateInfo fallbackPair(String base, String target, double amount) {
        String url = String.format("https://open.er-api.com/v6/pair/%s/%s", base.toUpperCase(), target.toUpperCase());
        PairResponse response = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(PairResponse.class);

        if (response == null || !"success".equals(response.result)) {
            throw new IllegalStateException("汇率免费端点返回错误");
        }

        return new ExchangeRateInfo(
                response.baseCode,
                response.targetCode,
                response.conversionRate,
                response.conversionRate * amount,
                "免费端点（每日更新一次）"
        );
    }

    @SuppressWarnings("unused")
    private record PairResponse(
            String result,
            @JsonProperty("base_code") String baseCode,
            @JsonProperty("target_code") String targetCode,
            @JsonProperty("conversion_rate") double conversionRate,
            @JsonProperty("conversion_result") double conversionResult,
            @JsonProperty("time_last_update_utc") String timeLastUpdateUtc,
            @JsonProperty("error-type") String errorType
    ) {
    }
}
