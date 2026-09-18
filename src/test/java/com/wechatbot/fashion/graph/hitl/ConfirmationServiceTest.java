package com.wechatbot.fashion.graph.hitl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HITL 确认：幂等创建、首次生效、结果重放、过期。 */
class ConfirmationServiceTest {

    @SuppressWarnings("unchecked")
    private static ConfirmationService serviceWithoutPersistence() {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new ConfirmationService(new JdbcConfirmationStore(provider));
    }

    @Test
    void requestIsIdempotentPerRunAndAction() {
        ConfirmationService service = serviceWithoutPersistence();

        ConfirmationRecord first = service.request("run-1", "user-1", ConfirmationService.ACTION_PAID_OPERATION);
        ConfirmationRecord second = service.request("run-1", "user-1", ConfirmationService.ACTION_PAID_OPERATION);

        assertThat(second.confirmationId()).isEqualTo(first.confirmationId());
        assertThat(second.status()).isEqualTo(ConfirmationRecord.STATUS_PENDING);
    }

    @Test
    void confirmTakesEffectOnlyOnceAndResultCanBeReplayed() {
        ConfirmationService service = serviceWithoutPersistence();
        ConfirmationRecord record = service.request("run-2", "user-2", ConfirmationService.ACTION_PAID_OPERATION);

        assertThat(service.markResolved(record, true, "已生成试穿图")).isTrue();
        // 第二次（消息重试/并发）不应再次生效
        assertThat(service.markResolved(record, true, "重复结果")).isFalse();

        ConfirmationRecord latest = service.latest("run-2", ConfirmationService.ACTION_PAID_OPERATION).orElseThrow();
        assertThat(service.resolvedReply(latest)).contains("已生成试穿图");
    }

    @Test
    void rejectionIsAlsoRecordedForReplay() {
        ConfirmationService service = serviceWithoutPersistence();
        ConfirmationRecord record = service.request("run-3", "user-3", ConfirmationService.ACTION_PAID_OPERATION);

        service.markResolved(record, false, "已取消");

        ConfirmationRecord latest = service.latest("run-3", ConfirmationService.ACTION_PAID_OPERATION).orElseThrow();
        assertThat(latest.status()).isEqualTo(ConfirmationRecord.STATUS_REJECTED);
        assertThat(service.resolvedReply(latest)).contains("已取消");
    }

    @Test
    void overduePendingBecomesExpiredAndLeavesPendingList() {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConfirmationStore store = new JdbcConfirmationStore(provider);

        store.createIfAbsent("run-4", "user-4", "PAID_OPERATION",
                Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(store.listPending(10)).hasSize(1);
        int expired = store.expireOverdue(Instant.now());
        assertThat(expired).isEqualTo(1);
        assertThat(store.listPending(10)).isEmpty();
    }

    @Test
    void unresolvedRecordHasNoReplay() {
        ConfirmationService service = serviceWithoutPersistence();
        ConfirmationRecord record = service.request("run-5", "user-5", ConfirmationService.ACTION_PAID_OPERATION);
        assertThat(service.resolvedReply(record)).isEmpty();
    }
}
