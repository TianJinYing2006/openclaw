package com.wechatbot.fashion.graph.hitl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HITL 确认记录存储。
 *
 * <p>核心语义：{@link #createIfAbsent} 以 (runId, action) 幂等创建；{@link #confirm}
 * 只在第一次生效（返回是否真正发生状态迁移），配合已存 {@code resultSummary} 实现恢复重放。
 */
public interface ConfirmationStore {

    /** 幂等创建：同 (runId, action) 已存在则返回既有记录（不重复插入）。 */
    ConfirmationRecord createIfAbsent(String runId, String userId, String action, Instant expiresAt);

    Optional<ConfirmationRecord> find(String confirmationId);

    Optional<ConfirmationRecord> findLatest(String runId, String action);

    /** 标记确认结果；仅当当前为 PENDING 时生效，返回是否发生迁移。 */
    boolean confirm(String confirmationId, boolean approved, String resultSummary);

    /** 标记「确认后已执行」，写入结果摘要；返回是否发生迁移。 */
    boolean markConsumed(String confirmationId, String resultSummary);

    /** 待确认列表（管理站展示）。 */
    List<ConfirmationRecord> listPending(int limit);

    /** 把已过期但仍为 PENDING 的记录置为 EXPIRED，返回处理条数。 */
    int expireOverdue(Instant now);
}
