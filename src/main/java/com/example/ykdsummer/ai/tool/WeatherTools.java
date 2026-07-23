package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.weather.WeatherInfo;
import com.example.ykdsummer.weather.WeatherService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的天气工具边界。业务查询仍由 WeatherService 完成。
 */
@Component
public class WeatherTools implements AiTool {

    private final WeatherService weatherService;
    private final AiTraceLogger trace;

    public WeatherTools(WeatherService weatherService) {
        this(weatherService, AiTraceLogger.disabled());
    }

    @Autowired
    public WeatherTools(WeatherService weatherService, AiTraceLogger trace) {
        this.weatherService = weatherService;
        this.trace = trace;
    }

    @Tool(
            name = "get_current_weather",
            description = "查询指定城市当前的实时天气、温度、湿度和风力。"
                    + "仅在用户询问现在或当前天气时调用；城市不明确时应先追问，不能猜测。"
    )
    public WeatherInfo getCurrentWeather(
            @ToolParam(
                    required = true,
                    description = "用户明确提供的城市名称，例如杭州、北京、上海，不要附加问题或说明文字。"
            )
            String city
    ) {
        trace.toolCall("get_current_weather", "city=" + city);
        try {
            WeatherInfo result = weatherService.getCurrentWeather(city);
            trace.toolResult("get_current_weather", result);
            return result;
        } catch (RuntimeException failure) {
            trace.toolFailure("get_current_weather", failure);
            throw failure;
        }
    }
}
