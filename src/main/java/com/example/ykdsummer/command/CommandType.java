package com.example.ykdsummer.command;

public enum CommandType {
    HELP("help", "显示帮助信息"),
    VERSION("version", "显示版本"),
    STATUS("status", "显示程序状态"),
    WEATHER("weather", "查询天气"),
    UNKNOWN("unknown", "未知命令");

    private final String code;
    private final String description;

    CommandType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public static CommandType from(String input) {
        // 没有输入内容
        if (input == null || input.trim().isEmpty()) {
            return UNKNOWN;
        }

        // 依次检查每一个命令
        for (CommandType command : CommandType.values()) {
            if (command.getCode().equalsIgnoreCase(input.trim())) {
                return command;
            }
        }

        // 没找到对应命令
        return UNKNOWN;
    }

    public String getDescription() {
        return description;
    }
}
