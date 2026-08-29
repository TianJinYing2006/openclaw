package com.wechatbot.fashion.tianxing;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 天行数据历史的今天查询业务服务。
 * 接口地址：https://apis.tianapi.com/lishi/index?key={apiKey}&date={MMDD}
 */
@Service
public class TodayInHistoryService {

    private static final String BASE_URL = "https://apis.tianapi.com";

    private final RestClient restClient;
    private final String apiKey;

    public TodayInHistoryService(
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
     * 查询今天的历史事件。
     */
    public List<TodayInHistoryInfo> getTodayEvents() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMdd"));
        return queryByDate(today);
    }

    /**
     * 查询指定月日的历史事件。
     *
     * @param month 月份 1-12
     * @param day   日期 1-31
     */
    public List<TodayInHistoryInfo> getEventsByDate(int month, int day) {
        String date = String.format("%02d%02d", month, day);
        return queryByDate(date);
    }

    private List<TodayInHistoryInfo> queryByDate(String date) {
        if (!isConfigured()) {
            throw new IllegalStateException("天行数据 API Key 未配置");
        }

        var response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/lishi/index")
                        .queryParam("key", apiKey)
                        .queryParam("date", date)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.code() != 200) {
            String msg = (response != null) ? response.msg() : "null";
            throw new IllegalStateException("历史事件查询失败: " + msg);
        }
        if (response.result() == null || response.result().isEmpty()) {
            return Collections.emptyList();
        }
        return response.result().stream()
                .map(item -> new TodayInHistoryInfo(item.title(), item.lsdate()))
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
            String title,
            String lsdate
    ) {
    }
}
