package com.example.demo.command;

import com.example.demo.ICommand;
import com.example.demo.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WeatherCommand implements ICommand {

    @Autowired
    private WeatherService weatherService;

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "查询天气预报，用法: /weather <城市名> [天数]";
    }

    @Override
    public String execute(String[] args) {
        if (args.length == 0) {
            return "❌ 请指定城市名，用法: /weather <城市名> [天数]\n" +
                   "例如: /weather 北京\n" +
                   "      /weather New York 2";
        }

        String city;
        int days = 3;

        // 尝试将最后一个参数解析为天数（如 "New York 2" → city="New York", days=2）
        if (args.length >= 2) {
            String lastArg = args[args.length - 1];
            try {
                int parsed = Integer.parseInt(lastArg);
                if (parsed >= 0 && parsed <= 3) {
                    days = parsed;
                    // 前 N-1 个参数拼接为城市名
                    city = String.join(" ", java.util.Arrays.copyOf(args, args.length - 1));
                    return weatherService.queryWeather(city, days);
                }
            } catch (NumberFormatException ignored) {
                // 最后一个参数不是数字，全部拼接为城市名
            }
        }

        // 所有参数拼接为城市名
        city = String.join(" ", args);
        return weatherService.queryWeather(city, days);
    }
}
