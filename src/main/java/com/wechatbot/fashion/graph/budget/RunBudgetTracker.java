package com.wechatbot.fashion.graph.budget;

import com.wechatbot.fashion.ai.config.AgentBudgetProperties;
import com.wechatbot.fashion.ai.orchestration.ToolGovernance;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次 Agent run 的执行预算跟踪器（内存态，run 结束即清理）。
 *
 * <p>由 {@code TrajectoryLifecycleListener.onStart} 以 {@code putIfAbsent} 初始化（暂停后恢复不重置计数），
 * 由 {@code onComplete/onError} 清理。{@code AgentLlmCaller} 在每次模型调用前 {@link #allowModelCall}，
 * 调用后 {@link #addTokens}；节点可通过 {@link #exceededReason} 主动降级。
 *
 * <p>{@code enabled=false} 时所有方法直接放行，零行为变化。
 */
@Component
public class RunBudgetTracker {

    /** 单个 run 的运行时预算状态。 */
    private static final class Budget {
        final long startedNanos = System.nanoTime();
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicLong tokens = new AtomicLong();
        final AtomicInteger toolCalls = new AtomicInteger();
        final ConcurrentHashMap<String, AtomicInteger> perTool = new ConcurrentHashMap<>();
        volatile String reason;

        Budget() {
        }
    }

    private final AgentBudgetProperties properties;
    private final ConcurrentHashMap<String, Budget> runs = new ConcurrentHashMap<>();

    public RunBudgetTracker(AgentBudgetProperties properties) {
        this.properties = properties;
    }

    /** 初始化 run 预算；已存在则不重置（恢复暂停 run 时保持累计）。 */
    public void begin(String runId) {
        if (!properties.isEnabled() || runId == null || runId.isBlank()) {
            return;
        }
        runs.putIfAbsent(runId, new Budget());
    }

    /** 是否允许再发起一次模型调用；不允许时记录原因。 */
    public boolean allowModelCall(String runId) {
        if (!properties.isEnabled() || runId == null || runId.isBlank()) {
            return true;
        }
        Budget budget = runs.get(runId);
        if (budget == null) {
            return true;
        }
        if (exceeded(budget)) {
            return false;
        }
        if (budget.tokens.get() >= properties.getMaxTotalTokens()) {
            budget.reason = "maxTotalTokens";
            return false;
        }
        if (budget.modelCalls.incrementAndGet() > properties.getMaxModelCalls()) {
            budget.reason = "maxModelCalls";
            return false;
        }
        return true;
    }

    /**
     * 是否允许再发起一次工具调用；受 run 全局工具次数上限与单工具 {@code maxCallsPerRun} 双重约束。
     * 与模型预算独立计数。
     */
    public boolean allowToolCall(String runId, String toolName) {
        if (!properties.isEnabled() || runId == null || runId.isBlank()) {
            return true;
        }
        Budget budget = runs.get(runId);
        if (budget == null) {
            return true;
        }
        if (exceeded(budget)) {
            return false;
        }
        if (budget.toolCalls.incrementAndGet() > properties.getMaxToolCallsPerRun()) {
            budget.reason = "maxToolCallsPerRun";
            return false;
        }
        int cap = ToolGovernance.policyOf(toolName).maxCallsPerRun();
        if (cap > 0) {
            int count = budget.perTool.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
            if (count > cap) {
                budget.reason = "toolBudget:" + toolName;
                return false;
            }
        }
        return true;
    }

    /** 累加一次模型调用实际消耗的 token。 */
    public void addTokens(String runId, long tokens) {
        if (!properties.isEnabled() || runId == null || tokens <= 0) {
            return;
        }
        Budget budget = runs.get(runId);
        if (budget == null) {
            return;
        }
        long total = budget.tokens.addAndGet(tokens);
        if (total >= properties.getMaxTotalTokens()) {
            budget.reason = "maxTotalTokens";
        }
    }

    /** 当前 run 是否已超预算；返回原因（无则 empty）。 */
    public Optional<String> exceededReason(String runId) {
        if (!properties.isEnabled() || runId == null) {
            return Optional.empty();
        }
        Budget budget = runs.get(runId);
        if (budget == null) {
            return Optional.empty();
        }
        if (exceeded(budget)) {
            return Optional.of(budget.reason == null ? "deadline" : budget.reason);
        }
        return Optional.empty();
    }

    /** run 结束后清理。 */
    public void finish(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }

    /** 当前是否已超过 run deadline。 */
    private boolean exceeded(Budget budget) {
        if (budget.reason != null) {
            return true;
        }
        if (System.nanoTime() - budget.startedNanos > properties.getRunDeadline().toNanos()) {
            budget.reason = "deadline";
            return true;
        }
        return false;
    }
}
