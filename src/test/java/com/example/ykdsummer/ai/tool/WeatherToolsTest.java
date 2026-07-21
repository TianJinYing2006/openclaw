package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.weather.WeatherInfo;
import com.example.ykdsummer.weather.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherToolsTest {

    @Test
    void exposesClearSchemaAndDelegatesToBusinessService() {
        WeatherService weatherService = mock(WeatherService.class);
        when(weatherService.getCurrentWeather("杭州")).thenReturn(new WeatherInfo(
                "浙江省", "杭州市", "小雨", 26,
                "西南风", "2级", 95, "5 分钟前发布"
        ));
        WeatherTools tools = new WeatherTools(weatherService);

        ToolCallback callback = ToolCallbacks.from(tools)[0];
        String result = callback.call("{\"city\":\"杭州\"}");

        assertThat(callback.getToolDefinition().name()).isEqualTo("get_current_weather");
        assertThat(callback.getToolDefinition().description())
                .contains("当前", "城市不明确时应先追问");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("city", "城市名称", "required");
        assertThat(result).contains("杭州市", "小雨", "26", "95");
        verify(weatherService).getCurrentWeather("杭州");
    }
}
