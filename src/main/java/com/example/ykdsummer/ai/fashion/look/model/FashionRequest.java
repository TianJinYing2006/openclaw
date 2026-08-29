package com.example.ykdsummer.ai.fashion.look.model;

/**
 * 穿搭推荐请求。由 FashionAgentService 构造，传入 AgentCoordinator。
 *
 * @param userId   微信用户 ID
 * @param userInput 用户原始输入，如 "今天我要去海边，帮我推荐一套穿搭"
 */
public record FashionRequest(String userId, String userInput) {
}
