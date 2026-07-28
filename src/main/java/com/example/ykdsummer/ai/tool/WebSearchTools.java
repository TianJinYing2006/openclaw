package com.example.ykdsummer.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String SEARCH_URL = "https://uapis.cn/api/v1/search/aggregate";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${uapis.api-key:not-configured}")
    private String apiKey;

    public WebSearchTools() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(
            name = "web_search",
            description = "搜索互联网获取实时信息。当用户询问最新新闻、实时事件、当前时间相关的问题、"
                    + "或者需要查询网上才能获得的信息时调用此工具。"
                    + "例如：今天有什么新闻、最新的科技动态、某个事件的最新进展、实时天气、股票价格等。"
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

        try {
            return searchWithUapi(query);
        } catch (Exception exception) {
            log.warn("Web search failed: {}", exception.getMessage());
            return "搜索失败，请稍后重试。错误信息：" + exception.getMessage();
        }
    }

    private String searchWithUapi(String query) throws IOException, InterruptedException {
        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("query", query);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "YKD-Summer-Bot/1.0")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        log.info("UAPI search starting, query length={}", query.length());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("UAPI search failed: status={}, body={}", response.statusCode(), response.body());
            if (response.statusCode() == 401) {
                return "搜索服务认证失败，请检查 API Key";
            }
            if (response.statusCode() == 429) {
                return "搜索请求过于频繁，请稍后重试";
            }
            return "搜索服务暂时不可用，HTTP 状态码：" + response.statusCode();
        }

        JsonNode root = objectMapper.readTree(response.body());

        // 检查是否是错误响应
        if (root.has("code") && root.has("message")) {
            return "搜索失败：" + root.path("message").asText();
        }

        return formatSearchResults(root, query);
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
