package com.wechatbot.fashion.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索 + 网页内容抓取。
 * <p>搜索使用 Brave Search API（免费 2000 次/月），
 * 网页内容抓取使用 Jsoup。</p>
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final String BRAVE_API = "https://api.search.brave.com/res/v1/web/search";
    private static final int MAX_CONTENT_LENGTH = 6000;

    private final RestClient restClient;
    private final String apiKey;

    public WebSearchService(
            RestClient.Builder restClientBuilder,
            @Value("${brave.api-key:}") String apiKey
    ) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
    }

    /**
     * 使用 Brave Search API 搜索网页。
     *
     * @param query 搜索关键词
     * @param count 结果数量（1-10）
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int count) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Brave Search API Key 未配置，请配置 brave.api-key");
        }
        if (count < 1) count = 1;
        if (count > 10) count = 10;

        String uri = BRAVE_API + "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&count=" + count;

        BraveResponse response = restClient.get()
                .uri(uri)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .retrieve()
                .body(BraveResponse.class);

        if (response == null || response.web() == null || response.web().results() == null) {
            log.warn("Brave Search 返回空结果, query={}", query);
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>();
        for (var r : response.web().results()) {
            results.add(new SearchResult(
                    r.title() != null ? r.title() : "",
                    r.url() != null ? r.url() : "",
                    r.description() != null ? r.description() : ""
            ));
        }
        return results;
    }

    /**
     * 使用 Jsoup 抓取网页正文内容。
     *
     * @param url 网页 URL
     * @return 抓取结果（标题 + 纯文本正文）
     */
    public FetchedPage fetchPage(String url) {
        try {
            org.jsoup.nodes.Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .followRedirects(true)
                    .get();

            String title = doc.title();
            // 移除脚本、样式等非正文元素
            doc.select("script, style, nav, footer, header, aside, iframe, .sidebar, .ad, .advertisement, .menu").remove();
            String text = doc.body() != null ? doc.body().text() : "";

            // 保留空白，JSOUP 已做 clean
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "...（以下内容已截断）";
            }

            return new FetchedPage(url, title, text);
        } catch (Exception e) {
            log.warn("抓取页面失败: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("抓取页面失败：" + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unused")
    private record BraveResponse(Web web) {
    }

    @SuppressWarnings("unused")
    private record Web(List<WebResult> results) {
    }

    @SuppressWarnings("unused")
    private record WebResult(
            String title,
            String url,
            String description
    ) {
    }
}
