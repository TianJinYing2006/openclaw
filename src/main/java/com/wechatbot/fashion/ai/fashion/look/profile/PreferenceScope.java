package com.wechatbot.fashion.ai.fashion.look.profile;

import java.time.Duration;
import java.time.Instant;

/**
 * 记忆治理：判断一条偏好是「长期偏好」还是「本次/短期上下文」。
 *
 * <p>动机：LLM 抽取可能把一次性表达（"这次不要红色"）误记为长期偏好，污染后续所有推荐。
 * 这里用保守的确定性关键词规则给短期表达打上 SESSION 作用域与过期时间，LONG_TERM 则永久有效，
 * 由 {@code JdbcFashionCoreRepository.preferences} 过滤过期项。
 *
 * @param scope     作用域 LONG_TERM / SESSION
 * @param expiresAt SESSION 的过期时间；LONG_TERM 为 null
 */
public record PreferenceScope(String scope, Instant expiresAt) {

    public static final String LONG_TERM = "LONG_TERM";
    public static final String SESSION = "SESSION";

    /** 一次性/当下语境的关键词：命中即视为短期偏好。 */
    private static final String[] SESSION_MARKERS = {
            "这次", "本次", "今天", "今晚", "这套", "这件", "先不", "暂时", "这会儿", "现在"
    };

    /** 短期偏好有效期（24h）。 */
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    public static PreferenceScope longTerm() {
        return new PreferenceScope(LONG_TERM, null);
    }

    /** 依据反馈文本与当前时间判定作用域。 */
    public static PreferenceScope classify(String feedbackText, Instant now) {
        if (feedbackText == null || feedbackText.isBlank()) {
            return longTerm();
        }
        for (String marker : SESSION_MARKERS) {
            if (feedbackText.contains(marker)) {
                return new PreferenceScope(SESSION, now.plus(SESSION_TTL));
            }
        }
        return longTerm();
    }

    public boolean isSession() {
        return SESSION.equals(scope);
    }
}
