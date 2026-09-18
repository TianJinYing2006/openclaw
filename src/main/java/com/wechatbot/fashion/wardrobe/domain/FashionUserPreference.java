package com.wechatbot.fashion.wardrobe.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 规范化用户偏好（供排序引擎加权）。
 *
 * @param scope     作用域：LONG_TERM（长期）/ SESSION（本次·短期），用于区分「一次性表达」与「长期偏好」
 * @param expiresAt 过期时间；null 表示长期有效（仅 SESSION 偏好会设置）
 */
public record FashionUserPreference(
        long appUserId,
        String dimensionCode,
        String valueCode,
        String polarity,
        BigDecimal weight,
        BigDecimal confidence,
        String source,
        String scope,
        String lastEvidence,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) { }
