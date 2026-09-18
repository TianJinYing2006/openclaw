package com.wechatbot.fashion.graph.hitl;

import java.time.Instant;

/**
 * HITL 确认记录。
 *
 * @param confirmationId 确认唯一 ID（创建时生成）
 * @param runId          关联的图 run（当前等于 threadId=userId），(runId, action) 唯一
 * @param userId         微信用户
 * @param action         逻辑动作（如 PAID_OPERATION），作为幂等键的一部分
 * @param status         PENDING / CONFIRMED / REJECTED / EXPIRED
 * @param resultSummary  确认后产出的用户可见结果（用于恢复重放，避免重复执行副作用）
 */
public record ConfirmationRecord(
        String confirmationId,
        String runId,
        String userId,
        String action,
        String status,
        String resultSummary,
        Instant createdAt,
        Instant confirmedAt,
        Instant expiresAt
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** 确认后已执行完毕（副作用已发生）；再次触发应重放结果而非重复执行。 */
    public static final String STATUS_CONSUMED = "CONSUMED";

    public boolean isResolved() {
        return STATUS_CONFIRMED.equals(status) || STATUS_REJECTED.equals(status)
                || STATUS_CONSUMED.equals(status);
    }

    public boolean isConsumed() {
        return STATUS_CONSUMED.equals(status);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
