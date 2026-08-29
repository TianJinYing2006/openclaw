package com.wechatbot.fashion.ai.service;

/** 把第三方 SDK 异常翻译成业务层能理解、且不会泄露响应正文的错误类型。 */
public class AiGatewayException extends RuntimeException {

    private final Kind kind;

    public AiGatewayException(Kind kind, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
    }

    public AiGatewayException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        /** API Key 无效、缺少权限或被服务端拒绝。 */
        AUTHENTICATION,
        /** 网络、超时、限流或服务端临时故障，用户稍后可以重试。 */
        TEMPORARY_UNAVAILABLE,
        /** 请求成功但没有可用的 output_text。 */
        EMPTY_RESPONSE,
        /** Agent 在同一请求内连续规划过多轮工具，已在执行下一轮前安全停止。 */
        AGENT_ROUND_LIMIT
    }
}
