package com.example.ykdsummer.ai.orchestration;

/**
 * 请求级会话上下文，通过 ThreadLocal 在工具方法间传递 userId/sessionId/contextToken。
 *
 * <p>{@code ILinkReplyService} 在调用 gateway 前设置，工具方法内通过
 * {@link #currentUserId()} / {@link #currentSessionId()} / {@link #currentContextToken()}
 * 获取，调用结束后清除。</p>
 */
public final class AgentSessionContext {

    private static final ThreadLocal<String> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> sessionIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> contextTokenHolder = new ThreadLocal<>();

    private AgentSessionContext() { }

    public static void set(String userId, String sessionId) {
        set(userId, sessionId, null);
    }

    public static void set(String userId, String sessionId, String contextToken) {
        userIdHolder.set(userId);
        sessionIdHolder.set(sessionId);
        contextTokenHolder.set(contextToken);
    }

    public static String currentUserId() {
        String id = userIdHolder.get();
        return id != null ? id : "anonymous";
    }

    public static String currentSessionId() {
        return sessionIdHolder.get();
    }

    /** 当前消息的 iLink contextToken，用于主动消息推送。可能为 null。 */
    public static String currentContextToken() {
        return contextTokenHolder.get();
    }

    public static void clear() {
        userIdHolder.remove();
        sessionIdHolder.remove();
        contextTokenHolder.remove();
    }
}
