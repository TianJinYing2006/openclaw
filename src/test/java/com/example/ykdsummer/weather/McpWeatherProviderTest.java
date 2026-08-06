package com.example.ykdsummer.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpWeatherProviderTest {

    private final WeatherProperties properties = new WeatherProperties();
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final McpConnectionManager mcp = mock(McpConnectionManager.class);
    private final McpWeatherProvider service = new McpWeatherProvider(mcp, properties);

    {
        // 测试环境用 mock 的 manager 委托到真实 findTool+call，保持原断言不变
        when(mcp.callTool(anyString(), anyString())).thenAnswer(invocation -> {
            ToolCallback tool = McpToolSupport.findTool(provider, invocation.getArgument(0));
            return tool == null ? null : tool.call(invocation.getArgument(1));
        });
    }

    @Test
    void parsesStructuredWeatherJsonIntoWeatherInfo() {
        ToolCallback tool = tool("get_weather", """
                {"province":"浙江省","city":"杭州市","weather":"小雨",
                 "temperature":26,"windDirection":"西南风","windPower":"2级",
                 "humidity":95,"reportTime":"5 分钟前发布"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("杭州");

        assertThat(result).isEqualTo(new WeatherInfo(
                "浙江省", "杭州市", "小雨", 26,
                "西南风", "2级", 95, "5 分钟前发布"));
    }

    @Test
    void normalizesCityBeforeCallingTheTool() {
        ToolCallback tool = tool("get_weather", """
                {"province":"湖北省","city":"武汉市","weather":"晴",
                 "temperature":32,"windDirection":"东南风","windPower":"3级",
                 "humidity":60,"reportTime":"刚刚发布"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("  武汉  ");

        assertThat(result.city()).isEqualTo("武汉市");
    }

    @Test
    void parsesMcpContentArrayWrappingTheBusinessJson() {
        // SyncMcpToolCallback.call() 返回的是 content 列表序列化，业务 JSON 藏在 text 字段里
        String businessJson = "{\"province\":\"广东省\",\"city\":\"广州市\",\"weather\":\"晴\","
                + "\"temperature\":30,\"windDirection\":\"南风\",\"windPower\":\"2级\","
                + "\"humidity\":70,\"reportTime\":\"刚刚发布\"}";
        String escaped = businessJson.replace("\"", "\\\"");
        ToolCallback tool = tool("get_weather", "[{\"type\":\"text\",\"text\":\"" + escaped + "\"}]");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("广州");

        assertThat(result).isEqualTo(new WeatherInfo(
                "广东省", "广州市", "晴", 30,
                "南风", "2级", 70, "刚刚发布"));
    }

    @Test
    void rejectsBlankCityBeforeCallingTheTool() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getCurrentWeather("  "))
                .withMessage("城市名称不能为空");
    }

    @Test
    void rejectsOverlyLongCityName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getCurrentWeather("a".repeat(51)))
                .withMessage("城市名称过长");
    }

    @Test
    void throwsWhenTheConfiguredToolIsNotExposed() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务未配置");
    }

    @Test
    void throwsWhenTheServerReturnsAnError() {
        ToolCallback tool = tool("get_weather", "{\"error\":\"city not found\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("未知城市"))
                .withMessageContaining("天气服务没有返回有效数据");
    }

    @Test
    void throwsWhenTheResponseIsMalformed() {
        ToolCallback tool = tool("get_weather", "not-json");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        // extractJson 将畸形输入归一化为 {}，city 缺失 → 没有返回有效数据
        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务没有返回有效数据");
    }

    @Test
    void throwsWhenTheCallFails() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("get_weather");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("timeout"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务调用失败");
    }

    @Test
    void toleratesMissingOptionalFields() {
        ToolCallback tool = tool("get_weather", """
                {"city":"深圳市","weather":"多云"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("深圳");

        assertThat(result.city()).isEqualTo("深圳市");
        assertThat(result.weather()).isEqualTo("多云");
        assertThat(result.province()).isEmpty();
        assertThat(result.temperatureCelsius()).isNull();
        assertThat(result.humidityPercent()).isNull();
        assertThat(result.windDirection()).isEmpty();
    }

    // --- helpers ---

    private static ToolCallback tool(String name, String response) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(response);
        return tool;
    }
}
