package com.example.ykdsummer.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 网络搜索工具。使用 SerpAPI/Google 搜索获取实时信息。
 *
 * <p>依赖环境变量 {@code SERPAPI_API_KEY} 进行认证。如果未配置，将返回友好提示。</p>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final String FALLBACK_MESSAGE = "搜索功能暂未配置 API Key";

    private final String apiKey;
    private final RestClient restClient;

    public WebSearchTools(
            @Value("${SERPAPI_API_KEY:}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    @Tool(name = "search_web", description = "搜索互联网上的实时信息，返回搜索结果摘要")
    public String searchWeb(
            @ToolParam(description = "搜索关键词") String query
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Web search disabled: SERPAPI_API_KEY not configured");
            return FALLBACK_MESSAGE;
        }
        try {
            String response = restClient.get()
                    .uri("https://serpapi.com/search.json?q={q}&api_key={key}&hl=zh-cn",
                            URLEncoder.encode(query, StandardCharsets.UTF_8), apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            log.info("Web search completed for query='{}'", query);
            return response != null ? response : "搜索无结果";
        } catch (RuntimeException exception) {
            log.warn("Web search failed for query='{}'", query, exception);
            return "搜索失败：" + exception.getMessage();
        }
    }
}
