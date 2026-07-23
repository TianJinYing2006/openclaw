package com.example.ykdsummer.ai.tool;

final class InformationToolSupport {

    private InformationToolSupport() {
    }

    static String unavailable(String capability) {
        return capability + "暂未配置，当前无法查询。";
    }

    static String failed(String capability, RuntimeException exception) {
        String message = exception.getMessage();
        return capability + "失败：" + (message == null || message.isBlank() ? "服务暂时不可用" : message);
    }
}
