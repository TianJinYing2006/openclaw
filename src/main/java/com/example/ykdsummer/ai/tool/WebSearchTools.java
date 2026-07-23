package com.example.ykdsummer.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 网络搜索工具。使用博查搜索 API 获取实时信息。
 *
 * <p>依赖配置项 {@code bocha.api-key} 进行认证。如果未配置，将返回友好提示。</p>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final String FALLBACK_MESSAGE = "搜索功能暂未配置 API Key";

    private final String apiKey;
    private final RestClient restClient;

    public WebSearchTools(
            @Value("${bocha.api-key:}") String apiKey
    ) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "search_web", description = "搜索互联网上的实时信息，返回搜索结果摘要")
    public String searchWeb(
            @ToolParam(description = "搜索关键词") String query
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Web search disabled: bocha.api-key not configured");
            return FALLBACK_MESSAGE;
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "query", query,
                    "freshness", "noLimit",
                    "count", 10,
                    "summary", true
            );

            Map<String, Object> response = restClient.post()
                    .uri("https://api.bocha.cn/v1/web-search")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("Bocha web search returned null response for query='{}'", query);
                return "搜索无结果";
            }

            // 博查 API 返回格式：{ code, msg, data: { webPages: { value: [...] } } }
            Object codeObj = response.get("code");
            if (codeObj == null || !"200".equals(codeObj.toString())) {
                log.warn("Bocha web search returned code={}, msg='{}' for query='{}'",
                        codeObj, response.get("msg"), query);
                return "搜索无结果（API 返回异常）";
            }

            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data == null) {
                log.warn("Bocha web search: response has no 'data' field, keys={}", response.keySet());
                return "搜索无结果";
            }

            Map<String, Object> webPages = (Map<String, Object>) data.get("webPages");
            if (webPages == null) {
                log.warn("Bocha web search: data has no 'webPages' field, keys={}", data.keySet());
                return "搜索无结果";
            }

            List<Map<String, Object>> values = (List<Map<String, Object>>) webPages.get("value");
            if (values == null || values.isEmpty()) {
                log.info("Bocha web search returned 0 results for query='{}'", query);
                return "搜索无结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(values.size()).append(" 条结果：\n\n");
            for (int i = 0; i < values.size(); i++) {
                Map<String, Object> item = values.get(i);
                sb.append(i + 1).append(". ");
                sb.append(item.getOrDefault("name", "")).append("\n");
                sb.append("   链接：").append(item.getOrDefault("url", "")).append("\n");
                sb.append("   摘要：").append(item.getOrDefault("snippet", "")).append("\n");
                if (item.get("dateLastCrawled") != null) {
                    sb.append("   日期：").append(item.get("dateLastCrawled")).append("\n");
                }
                sb.append("\n");
            }

            log.info("Bocha web search completed for query='{}', results={}", query, values.size());
            return sb.toString();
        } catch (RuntimeException exception) {
            log.warn("Web search failed for query='{}'", query, exception);
            return "搜索失败：" + exception.getMessage();
        }
    }
}
