package com.wechatbot.fashion.tianxing;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 星座运势业务服务。调用天行数据 star 接口。
 */
@Service
public class HoroscopeService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final String apiKey;

    public HoroscopeService(
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
     * 查询指定星座的今日运势。
     *
     * @param astro 星座中文名（如"白羊座"）或英文名（如"aries"）
     * @param date  日期 yyyy-MM-dd，为空时默认当天
     * @return 运势结果
     */
    public HoroscopeInfo queryHoroscope(String astro, String date) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }

        String url = "/star/index?key=" + apiKey
                + "&astro=" + java.net.URLEncoder.encode(astro, java.nio.charset.StandardCharsets.UTF_8);
        if (date != null && !date.isBlank()) {
            url += "&date=" + date;
        }

        ApiResponse response = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.code() != 200) {
            throw new IllegalStateException("星座运势查询失败: "
                    + (response != null ? response.msg() : "无响应"));
        }
        if (response.result() == null) {
            throw new IllegalStateException("星座运势返回数据为空");
        }

        return new HoroscopeInfo(response.result().type(), response.result().content());
    }

    @SuppressWarnings("unused")
    private record ApiResponse(
            int code,
            String msg,
            Result result
    ) {
    }

    private record Result(
            String type,
            String content
    ) {
    }
}
