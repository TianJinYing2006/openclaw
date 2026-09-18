package com.wechatbot.fashion.graph.hitl;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * HITL 确认服务：请求确认（幂等创建）+ 幂等恢复（已解析则重放既有结果，避免重复执行副作用）。
 *
 * <p>幂等键 = (runId, action)。用户对同一暂停 run 的"确认"若被重复投递（消息重试/并发），
 * 第二次起直接返回第一次的结果文案，不再重新运行图或重复触发付费操作。
 */
@Service
public class ConfirmationService {

    /** 付费/高费用操作确认动作。 */
    public static final String ACTION_PAID_OPERATION = "PAID_OPERATION";
    /** 确认有效期。 */
    private static final Duration TTL = Duration.ofHours(24);

    private final ConfirmationStore store;

    public ConfirmationService(ConfirmationStore store) {
        this.store = store;
    }

    /** 请求确认（幂等）：同 (runId, action) 已存在则返回既有记录。 */
    public ConfirmationRecord request(String runId, String userId, String action) {
        return store.createIfAbsent(runId, userId, action, Instant.now().plus(TTL));
    }

    public Optional<ConfirmationRecord> latest(String runId, String action) {
        return store.findLatest(runId, action);
    }

    /**
     * 若该确认已解析（CONFIRMED/REJECTED），返回已存结果文案用于**幂等重放**；否则 empty。
     */
    public Optional<String> resolvedReply(ConfirmationRecord record) {
        if (record == null || !record.isResolved()) {
            return Optional.empty();
        }
        return Optional.of(record.resultSummary() == null ? "" : record.resultSummary());
    }

    /** 标记确认结果（仅首次 PENDING → 生效）。 */
    public boolean markResolved(ConfirmationRecord record, boolean approved, String reply) {
        if (record == null) {
            return false;
        }
        return store.confirm(record.confirmationId(), approved, reply);
    }

    public int expireOverdue() {
        return store.expireOverdue(Instant.now());
    }

    public List<ConfirmationRecord> pending(int limit) {
        return store.listPending(limit);
    }
}
