package com.example.ykdsummer.weather;

import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部天气 MCP Server 工具替代 uapis.cn 公共 API。
 *
 * <p>外部 Server 接收城市名，返回结构化天气 JSON。本实现负责解析响应并映射到
 * {@link WeatherInfo}，保持与 {@link WeatherService} 相同的领域模型。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.weather", name = "provider", havingValue = "mcp")
public class McpWeatherProvider implements WeatherProvider {
    private static final Logger log = LoggerFactory.getLogger(McpWeatherProvider.class);

    private final SyncMcpToolCallbackProvider toolProvider;
    private final WeatherProperties properties;

    public McpWeatherProvider(SyncMcpToolCallbackProvider toolProvider, WeatherProperties properties) {
        this.toolProvider = toolProvider;
        this.properties = properties;
    }

    @Override
    public WeatherInfo getCurrentWeather(String city) {
        String normalizedCity = normalizeCity(city);
        String toolName = properties.getMcp().getToolName();
        try {
            ToolCallback tool = McpToolSupport.findTool(toolProvider, toolName);
            if (tool == null) {
                log.warn("Weather MCP tool not found, tool={}", toolName);
                throw new IllegalStateException("天气服务未配置: " + toolName);
            }
            String jsonArgs = McpToolSupport.objectMapper().createObjectNode()
                    .put("city", normalizedCity)
                    .toString();
            String raw = tool.call(jsonArgs);
            return parse(raw);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Weather MCP call failed, tool={}, type={}", toolName,
                    exception.getClass().getSimpleName(), exception);
            throw new IllegalStateException("天气服务调用失败，请稍后重试");
        }
    }

    /** Parses the MCP result JSON into the stable WeatherInfo domain record. */
    WeatherInfo parse(String raw) {
        JsonNode node;
        try {
            node = McpToolSupport.objectMapper().readTree(McpToolSupport.extractJson(raw));
        } catch (Exception exception) {
            throw new IllegalStateException("天气服务返回格式异常");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("天气服务返回格式异常");
        }
        String cityName = text(node.path("city"), 64);
        if (cityName.isBlank()) {
            throw new IllegalStateException("天气服务没有返回有效数据");
        }
        return new WeatherInfo(
                text(node.path("province"), 64),
                cityName,
                text(node.path("weather"), 64),
                intValue(node.path("temperature")),
                text(node.path("windDirection"), 32),
                text(node.path("windPower"), 32),
                intValue(node.path("humidity")),
                text(node.path("reportTime"), 128)
        );
    }

    private static String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
        String normalized = city.strip();
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("城市名称过长");
        }
        return normalized;
    }

    private static String text(JsonNode node, int limit) {
        if (node == null || node.isNull() || !node.isTextual()) return "";
        String value = node.asText("").replace('\u0000', ' ').strip();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static Integer intValue(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) return null;
        return node.asInt();
    }
}
