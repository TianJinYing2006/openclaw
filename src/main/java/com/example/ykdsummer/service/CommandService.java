package com.example.ykdsummer.service;

import com.example.ykdsummer.command.CommandType;
import com.example.ykdsummer.exception.InvalidCommandException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommandService {

    @Autowired
    private WeatherService weatherService;

    public String execute(CommandType commandType, String city) {
        switch (commandType) {
            case HELP:
                return """
                        可用命令：
                        help - 显示帮助信息
                        version - 显示版本
                        status - 显示程序状态
                        weather - 查询天气
                        """;

            case VERSION:
                return "当前版本：1.0.0";

            case STATUS:
                return "程序运行正常";

            case WEATHER:
                return weatherService.queryWeather(city);

            case UNKNOWN:
            default:
                throw new InvalidCommandException("错误：未知命令");
        }
    }
}
