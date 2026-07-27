package com.example.ykdsummer.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.ykdsummer.ai.config.AiUsageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 当前进程内的“用户 + 日期”模型用量账本。
 *
 * <p>请求前先预留预算，避免多条并发消息同时越过额度；成功后如果上游返回标准 usage，就用
 * 真实 token 结算。上游没有 usage 时才使用预留值作为估算。数据只在内存，重启后清空。</p>
 */
@Component
public class AiUsageMeter {
    public static final String DAILY_LIMIT_REPLY = "今天的 AI 使用额度已接近上限，请明天再试或联系管理员调整额度。";
    public static final String INPUT_TOO_LARGE_REPLY = "这次内容或文件预计过大，请拆分后再发送。";
    private static final Logger log = LoggerFactory.getLogger(AiUsageMeter.class);

    private final AiUsageProperties properties;
    private final Clock clock;
    private final boolean disabled;
    private final Cache<DailyUserKey, DailyLedger> ledgers;
    private volatile UsageEventRecorder eventRecorder = UsageEventRecorder.disabled();

    @Autowired
    public AiUsageMeter(AiUsageProperties properties) { this(properties, Clock.systemDefaultZone(), false); }
    AiUsageMeter(AiUsageProperties properties, Clock clock) { this(properties, clock, false); }
    private AiUsageMeter(AiUsageProperties properties, Clock clock, boolean disabled) {
        this.properties = properties;
        this.clock = clock;
        this.disabled = disabled;
        this.ledgers = Caffeine.newBuilder().maximumSize(properties.getMaxTrackedUsers())
                .expireAfterAccess(Duration.ofDays(2)).build();
    }
    /** 供不启动 Spring 容器的旧单元测试使用；不会拒绝也不会记录。 */
    public static AiUsageMeter disabled() { return new AiUsageMeter(new AiUsageProperties(), Clock.systemUTC(), true); }

    @Autowired(required = false)
    void setEventRecorder(UsageEventRecorder eventRecorder) {
        this.eventRecorder = eventRecorder == null ? UsageEventRecorder.disabled() : eventRecorder;
    }

    /** 在真正调用 Chat Completions 或 Responses 前预留本轮最坏情况额度。 */
    public Reservation reserve(String userId, AiRequestBudget budget) {
        if (disabled) return Reservation.bypassed();
        if (!properties.isEnabled()) return Reservation.unlimited(safeUser(userId), budget == null ? 0L : budget.reservedTokens());
        if (budget.estimatedInputTokens() > properties.getMaxEstimatedInputTokens()) {
            return Reservation.rejected(safeUser(userId), RejectReason.INPUT_TOO_LARGE);
        }
        DailyUserKey key = new DailyUserKey(safeUser(userId), LocalDate.now(clock));
        DailyLedger ledger = ledgers.get(key, ignored -> new DailyLedger());
        synchronized (ledger) {
            long requested = budget.reservedTokens();
            long current = ledger.reportedTotalTokens + ledger.estimatedFallbackTokens + ledger.reservedTokens;
            if (properties.getDailyTokenLimit() > 0 && exceedsLimit(current, requested, properties.getDailyTokenLimit())) {
                return Reservation.rejected(safeUser(userId), RejectReason.DAILY_LIMIT);
            }
            ledger.reservedTokens += requested;
            return Reservation.accepted(safeUser(userId), ledger, requested);
        }
    }

    /** 成功后结算。reported=true 使用服务商实际 usage；否则只记保守估算。 */
    public void complete(Reservation reservation, String protocol, String model, AiModelUsage usage) {
        complete(reservation, protocol, model, usage, 0);
    }

    /** 成功后结算，并写入本次端到端模型调用耗时。 */
    public void complete(Reservation reservation, String protocol, String model, AiModelUsage usage, long durationMs) {
        if (reservation == null || !reservation.close() || reservation.disabled) return;
        AiModelUsage safeUsage = usage == null ? AiModelUsage.unknown() : usage;
        if (reservation.ledger != null) {
            synchronized (reservation.ledger) {
                reservation.ledger.reservedTokens = Math.max(0L, reservation.ledger.reservedTokens - reservation.reservedTokens);
                if (safeUsage.reported()) {
                    reservation.ledger.reportedPromptTokens += safeUsage.promptTokens();
                    reservation.ledger.reportedCompletionTokens += safeUsage.completionTokens();
                    reservation.ledger.reportedTotalTokens += safeUsage.totalTokens();
                } else {
                    reservation.ledger.estimatedFallbackTokens += reservation.reservedTokens;
                }
                reservation.ledger.requestCount++;
                reservation.ledger.requestsByProtocol.merge(safeName(protocol), 1L, Long::sum);
            }
        }
        eventRecorder.recordModel(reservation.userId, protocol, model, safeUsage, reservation.reservedTokens, durationMs);
        log.debug("AI usage settled, user={}, protocol={}, model={}, durationMs={}, reportedUsage={}, totalTokens={}",
                anonymize(reservation.userId), safeName(protocol), safeName(model), Math.max(0, durationMs),
                safeUsage.reported(), safeUsage.reported() ? safeUsage.totalTokens() : reservation.reservedTokens);
    }

    /** Releases the reservation and records the failed upstream attempt without charging token usage. */
    public void fail(Reservation reservation, String protocol, String model, String reason, long durationMs) {
        if (reservation == null || !reservation.close() || reservation.disabled) return;
        if (reservation.ledger != null) {
            synchronized (reservation.ledger) {
                reservation.ledger.reservedTokens = Math.max(0L, reservation.ledger.reservedTokens - reservation.reservedTokens);
            }
        }
        eventRecorder.recordModelFailure(reservation.userId, protocol, model, safeName(reason), durationMs);
        log.debug("AI usage failed, user={}, protocol={}, model={}, durationMs={}, reason={}",
                anonymize(reservation.userId), safeName(protocol), safeName(model), Math.max(0, durationMs), safeName(reason));
    }

    /** 调用失败、超时或认证失败时释放预留，不把失败请求计入已消费额度。 */
    public void release(Reservation reservation) {
        if (reservation == null || !reservation.close() || reservation.ledger == null) return;
        synchronized (reservation.ledger) {
            reservation.ledger.reservedTokens = Math.max(0L, reservation.ledger.reservedTokens - reservation.reservedTokens);
        }
    }

    /** 仅供本地状态/测试使用的当前用户摘要；不包含提示词、文件名或密钥。 */
    public UsageSnapshot snapshot(String userId) {
        DailyUserKey key = new DailyUserKey(safeUser(userId), LocalDate.now(clock));
        DailyLedger ledger = ledgers.getIfPresent(key);
        if (ledger == null) return UsageSnapshot.empty(key.date, properties.getDailyTokenLimit(), properties.isEnabled() && !disabled);
        synchronized (ledger) {
            return new UsageSnapshot(key.date, properties.isEnabled() && !disabled, properties.getDailyTokenLimit(),
                    ledger.reportedPromptTokens, ledger.reportedCompletionTokens, ledger.reportedTotalTokens,
                    ledger.estimatedFallbackTokens, ledger.reservedTokens, ledger.requestCount, Map.copyOf(ledger.requestsByProtocol));
        }
    }

    private static boolean exceedsLimit(long current, long requested, long limit) { return current > limit || requested > limit - current; }
    private static String safeUser(String userId) { return userId == null || userId.isBlank() ? "unknown" : userId; }
    private static String safeName(String value) { return value == null || value.isBlank() ? "unknown" : value.strip(); }
    private static String anonymize(String userId) { return userId == null ? "unknown" : Integer.toHexString(userId.hashCode()); }
    public enum RejectReason { DAILY_LIMIT, INPUT_TOO_LARGE }

    public static final class Reservation {
        private final String userId;
        private final DailyLedger ledger;
        private final long reservedTokens;
        private final RejectReason rejectReason;
        private final boolean disabled;
        private boolean closed;
        private Reservation(String userId, DailyLedger ledger, long reservedTokens, RejectReason rejectReason, boolean disabled) {
            this.userId = userId; this.ledger = ledger; this.reservedTokens = reservedTokens; this.rejectReason = rejectReason; this.disabled = disabled;
        }
        static Reservation accepted(String userId, DailyLedger ledger, long reservedTokens) { return new Reservation(userId, ledger, reservedTokens, null, false); }
        static Reservation unlimited(String userId, long estimatedTokens) { return new Reservation(userId, null, Math.max(0L, estimatedTokens), null, false); }
        static Reservation bypassed() { return new Reservation("", null, 0L, null, true); }
        static Reservation rejected(String userId, RejectReason reason) { return new Reservation(userId, null, 0L, reason, false); }
        public boolean allowed() { return rejectReason == null; }
        public RejectReason rejectReason() { return rejectReason; }
        private synchronized boolean close() { if (closed) return false; closed = true; return true; }
    }

    public record UsageSnapshot(LocalDate date, boolean enabled, long dailyTokenLimit, long reportedPromptTokens,
                                long reportedCompletionTokens, long reportedTotalTokens, long estimatedFallbackTokens,
                                long reservedTokens, long requestCount, Map<String, Long> requestsByProtocol) {
        static UsageSnapshot empty(LocalDate date, long limit, boolean enabled) {
            return new UsageSnapshot(date, enabled, limit, 0L, 0L, 0L, 0L, 0L, 0L, Map.of());
        }
        public long accountedTokens() { return reportedTotalTokens + estimatedFallbackTokens; }
    }
    private record DailyUserKey(String userId, LocalDate date) { }
    private static final class DailyLedger {
        private long reportedPromptTokens;
        private long reportedCompletionTokens;
        private long reportedTotalTokens;
        private long estimatedFallbackTokens;
        private long reservedTokens;
        private long requestCount;
        private final Map<String, Long> requestsByProtocol = new HashMap<>();
    }
}
