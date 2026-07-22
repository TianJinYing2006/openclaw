package com.example.ykdsummer.ai.orchestration;

/**
 * 请求级会话上下文，通过 ThreadLocal 在工具方法间传递 userId/sessionId。
 *
 * <p>{@code ILinkReplyService} 在调用 gateway 前设置，工具方法内通过
 * {@link #currentUserId()} / {@link #currentSessionId()} 获取，调用结束后清除。</p>
 */
public final class AgentSessionContext {

    private static final ThreadLocal<String> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> sessionIdHolder = new ThreadLocal<>();

    private AgentSessionContext() { }

    public static void set(String userId, String sessionId) {
        userIdHolder.set(userId);
        sessionIdHolder.set(sessionId);
    }

    public static String currentUserId() {
        String id = userIdHolder.get();
        return id != null ? id : "anonymous";
    }

    public static String currentSessionId() {
        String id = sessionIdHolder.get();
        return id != null ? id : "tool";
    }

    public static void clear() {
        userIdHolder.remove();
        sessionIdHolder.remove();
    }
}
