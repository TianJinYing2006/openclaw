package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 写入用户偏好的更新请求。
 *
 * @param scope     作用域 LONG_TERM / SESSION（默认 LONG_TERM）
 * @param expiresAt 过期时间；SESSION 偏好应设置，LONG_TERM 传 null
 */
public record FashionPreferenceUpdate(
        String dimensionCode,
        String valueCode,
        String polarity,
        BigDecimal weight,
        BigDecimal confidence,
        String source,
        String evidence,
        String scope,
        Instant expiresAt
) {

    /** 兼容旧调用：默认长期有效。 */
    public FashionPreferenceUpdate(String dimensionCode, String valueCode, String polarity,
                                   BigDecimal weight, BigDecimal confidence, String source, String evidence) {
        this(dimensionCode, valueCode, polarity, weight, confidence, source, evidence, "LONG_TERM", null);
    }
}
