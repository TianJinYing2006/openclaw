package com.wechatbot.fashion.ai.tool;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 使用博查 API 检索可公开访问的实时网页信息。 */
@Component
@ConditionalOnProperty(prefix = "app.web-search", name = "provider",
        havingValue = "bocha", matchIfMissing = true)
public class BochaWebSearchTools implements WebSearchProvider {

    private static final String NOT_CONFIGURED = "博查搜索未配置 API Key";

    private final String apiKey;
    private final RestClient restClient;

    @Autowired
    public BochaWebSearchTools(@Value("${bocha.api-key:}") String apiKey) {
        this(apiKey, RestClient.create());
    }

    BochaWebSearchTools(String apiKey, RestClient restClient) {
        this.apiKey = apiKey;
        this.restClient = restClient;
    }

    @Override
    public String search(String query) {
        return searchWeb(query);
    }

    @Tool(name = "search_web", description = "使用博查搜索公开网页中的实时信息、新闻和热点。"
            + "只有问题需要最新外部事实时才调用；返回来源链接和摘要。")
    @SuppressWarnings("unchecked")
    public String searchWeb(@ToolParam(required = true, description = "要搜索的关键词或完整问题") String query) {
        if (apiKey == null || apiKey.isBlank()) {
            return NOT_CONFIGURED;
        }
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("https://api.bocha.cn/v1/web-search")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query.strip(), "freshness", "noLimit", "count", 8, "summary", true))
                    .retrieve()
                    .body(Map.class);
            if (response == null || !"200".equals(String.valueOf(response.get("code")))) {
                return "博查搜索没有可用结果";
            }
            Object dataValue = response.get("data");
            if (!(dataValue instanceof Map<?, ?> data)) {
                return "博查搜索没有可用结果";
            }
            Object pagesValue = data.get("webPages");
            if (!(pagesValue instanceof Map<?, ?> pages) || !(pages.get("value") instanceof List<?> values)
                    || values.isEmpty()) {
                return "博查搜索没有可用结果";
            }
            StringBuilder result = new StringBuilder("博查搜索结果：\n");
            int index = 1;
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> item) || index > 8) {
                    continue;
                }
                result.append(index++).append(". ").append(valueOf(item.get("name"))).append('\n')
                        .append("来源：").append(valueOf(item.get("url"))).append('\n')
                        .append("摘要：").append(valueOf(item.get("snippet"))).append("\n\n");
            }
            return index == 1 ? "博查搜索没有可用结果" : result.toString().strip();
        } catch (RuntimeException exception) {
            return "博查搜索失败：" + safeMessage(exception);
        }
    }

    private static String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "服务暂时不可用" : message;
    }
}
