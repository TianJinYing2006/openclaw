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

    /**
     * 要求当前请求必须绑定真实用户 ID，未设置时立即失败。
     *
     * <p>业务核心调用方应使用此方法而非 {@link #currentUserId()}，避免在上下文未初始化时
     * 静默使用 anonymous 身份执行，导致数据串户。辅助调用方（日志、追踪）可继续使用
     * {@link #currentUserId()} 的宽松语义。</p>
     *
     * @throws IllegalStateException 如果当前线程未设置用户 ID
     */
    public static String requireUserId() {
        String id = userIdHolder.get();
        if (id == null) {
            throw new IllegalStateException(
                    "AgentSessionContext not initialized: no userId bound to current thread");
        }
        return id;
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
