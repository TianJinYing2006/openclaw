package com.wechatbot.fashion.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 联网搜索工具（当前未注册，仅保留参考）：使用百炼 DashScope Web Search API。
 *
 * <p>实际在用的搜索工具为 {@link BochaWebSearchTools}（search_web）。</p>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);
    private static final URI SEARCH_URL = URI.create("https://uapis.cn/api/v1/search/aggregate");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI searchUrl;
    private final String apiKey;
    private final RealtimeSearchFallback realtimeSearchFallback;

    public WebSearchTools() {
        this("", RealtimeSearchFallback.unavailable());
    }

    @Autowired
    public WebSearchTools(
            @Value("${uapis.api-key:not-configured}") String apiKey,
            RealtimeSearchFallback realtimeSearchFallback
    ) {
        this(apiKey, realtimeSearchFallback, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    WebSearchTools(String apiKey, RealtimeSearchFallback realtimeSearchFallback, HttpClient httpClient) {
        this(apiKey, realtimeSearchFallback, httpClient, SEARCH_URL);
    }

    WebSearchTools(
            String apiKey,
            RealtimeSearchFallback realtimeSearchFallback,
            HttpClient httpClient,
            URI searchUrl
    ) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.realtimeSearchFallback = realtimeSearchFallback;
        this.searchUrl = searchUrl;
    }

    @Tool(
            name = "web_search",
            description = "搜索互联网获取实时信息。当用户询问最新新闻、实时事件、当前时间相关的问题、"
                    + "或者需要查询网上才能获得的信息时调用此工具。"
                    + "“本届/这一届、本赛季、今年、最近、当前排名、截至现在”等即使未明确说“最新”，"
                    + "只要答案会随时间或事件进展改变，也应优先联网核实。"
                    + "例如：今天有什么新闻、这一届世界杯冠军、最新的科技动态、某个事件的最新进展、实时天气、汇率变化等。"
                    + "如果问题是关于常识或不需要实时信息，则不要调用。"
    )
    public String webSearch(
            @ToolParam(
                    required = true,
                    description = "搜索关键词或问题"
            )
            String query
    ) {
        log.info("Web search request: {}", query);
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        try {
            return searchWithUapi(query);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallbackToBocha(query, "UAPIs 请求被中断");
        } catch (Exception exception) {
            log.warn("Web search failed: {}", exception.getMessage());
            return fallbackToBocha(query, "UAPIs 请求失败");
        }
    }

    private String searchWithUapi(String query) throws IOException, InterruptedException {
        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("query", query);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(searchUrl)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "YKD-Summer-Bot/1.0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (isUapisApiKeyConfigured()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey.strip());
        }
        HttpRequest request = requestBuilder.build();

        log.info("UAPI search starting, query length={}", query.length());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("UAPI search failed: status={}, body={}", response.statusCode(), response.body());
            throw new IllegalStateException("UAPIs HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());

        // 检查是否是错误响应
        if (root.has("code") && root.has("message")) {
            throw new IllegalStateException("UAPIs 返回业务错误");
        }

        return formatSearchResults(root, query);
    }

    private boolean isUapisApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equalsIgnoreCase(apiKey.strip());
    }

    private String fallbackToBocha(String query, String reason) {
        return realtimeSearchFallback.search("联网搜索", query.strip(), reason);
    }

    private String formatSearchResults(JsonNode root, String query) {
        StringBuilder result = new StringBuilder();
        result.append("🔍 搜索结果：").append(query).append("\n\n");

        // 获取搜索结果
        JsonNode results = root.path("results");
        if (results.isArray() && !results.isEmpty()) {
            int count = 0;
            for (JsonNode item : results) {
                if (count >= 5) break;
                String title = item.path("title").asText("");
                String url = item.path("url").asText("");
                String snippet = item.path("snippet").asText("");
                String source = item.path("source").asText("");

                if (!title.isBlank()) {
                    result.append("**").append(count + 1).append(". ").append(title).append("**\n");
                    if (!snippet.isBlank()) {
                        // 截取前150个字符
                        String shortSnippet = snippet.length() > 150
                                ? snippet.substring(0, 150) + "..."
                                : snippet;
                        result.append("   ").append(shortSnippet).append("\n");
                    }
                    if (!source.isBlank()) {
                        result.append("   来源：").append(source).append("\n");
                    }
                    if (!url.isBlank()) {
                        result.append("   🔗 ").append(url).append("\n");
                    }
                    result.append("\n");
                    count++;
                }
            }
        }

        // 如果没有结果
        if (result.length() < 30) {
            result.append("未找到相关搜索结果");
        }

        return result.toString();
    }
}
