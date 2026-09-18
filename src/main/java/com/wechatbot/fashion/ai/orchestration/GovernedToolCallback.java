package com.wechatbot.fashion.ai.orchestration;

import com.wechatbot.fashion.graph.budget.RunBudgetTracker;
import com.wechatbot.fashion.graph.hitl.ConfirmationRecord;
import com.wechatbot.fashion.graph.hitl.ConfirmationService;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具治理执行层：在统一 ToolCallback 边界施加 **调用预算 + 超时 + 审计**，避免治理逻辑散落到各工具内部。
 *
 * <p>行为：
 * <ul>
 *   <li><b>预算</b>：run 内全局工具次数 + 单工具 {@code maxCallsPerRun}，超限直接拒绝（不触达真实工具）；</li>
 *   <li><b>超时</b>：按 {@link ToolPolicy#timeoutMillis()} 执行，超时中断并返回超时文案；</li>
 *   <li><b>审计</b>：写一条 {@code TYPE_TOOL} 轨迹 step（工具名/风险/耗时/状态/脱敏输入哈希），
 *       有 runId 时生效。</li>
 * </ul>
 *
 * <p>治理组件缺失（预算/审计/执行器为 null）时按能力降级，仍保证超时逻辑正确（无执行器则内联执行、不超时）。
 */
public final class GovernedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GovernedToolCallback.class);

    private final ToolCallback delegate;
    private final ToolPolicy policy;
    private final RunBudgetTracker budgetTracker;
    private final AgentTrajectoryRecorder auditRecorder;
    private final ExecutorService executor;
    /** 工具级 HITL 确认（{@link ToolPolicy#requiresConfirmation()} 为真时生效）；可空。 */
    private final ConfirmationService confirmationService;

    /** 生产用：策略按工具名从 {@link ToolGovernance} 解析。 */
    public GovernedToolCallback(ToolCallback delegate, RunBudgetTracker budgetTracker,
                                AgentTrajectoryRecorder auditRecorder, ExecutorService executor) {
        this(delegate, ToolGovernance.policyOf(delegate.getToolDefinition().name()),
                budgetTracker, auditRecorder, executor, null);
    }

    /** 生产用（含 HITL）：策略按工具名解析。 */
    public GovernedToolCallback(ToolCallback delegate, RunBudgetTracker budgetTracker,
                                AgentTrajectoryRecorder auditRecorder, ExecutorService executor,
                                ConfirmationService confirmationService) {
        this(delegate, ToolGovernance.policyOf(delegate.getToolDefinition().name()),
                budgetTracker, auditRecorder, executor, confirmationService);
    }

    /** 可注入策略（测试用）。 */
    public GovernedToolCallback(ToolCallback delegate, ToolPolicy policy, RunBudgetTracker budgetTracker,
                                AgentTrajectoryRecorder auditRecorder, ExecutorService executor) {
        this(delegate, policy, budgetTracker, auditRecorder, executor, null);
    }

    public GovernedToolCallback(ToolCallback delegate, ToolPolicy policy, RunBudgetTracker budgetTracker,
                                AgentTrajectoryRecorder auditRecorder, ExecutorService executor,
                                ConfirmationService confirmationService) {
        this.delegate = delegate;
        this.policy = policy;
        this.budgetTracker = budgetTracker;
        this.auditRecorder = auditRecorder;
        this.executor = executor;
        this.confirmationService = confirmationService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        TrajectoryRunContext.Ref ref = TrajectoryRunContext.current();
        // 图内优先用图 runId；图外（对话网关主模型选工具）回退到网关作用域 runId
        String runId = ref.present() ? ref.runId() : ToolCallScope.current();
        String node = ref.present() ? ref.node() : null;

        if (budgetTracker != null && runId != null && !budgetTracker.allowToolCall(runId, name)) {
            String reason = budgetTracker.exceededReason(runId).orElse("tool-budget");
            log.warn("Tool call denied by budget: tool={}, reason={}", name, reason);
            audit(runId, node, name, "DENIED", 0, "budget:" + reason, toolInput);
            return "工具调用被拒绝：已达预算上限（" + name + "）。";
        }

        // HITL 工具级闸门：付费且需确认的工具（虚拟试衣）在执行前需用户确认，确认后仅执行一次
        ConfirmationRecord gate = null;
        if (policy.requiresConfirmation() && confirmationService != null) {
            String uid = AgentSessionContext.currentUserId();
            gate = confirmationService.latest(uid, name).orElse(null);
            if (gate == null) {
                confirmationService.request(uid, uid, name);
                audit(runId, node, name, "DENIED", 0, "await-confirmation", toolInput);
                return "试穿会消耗生成额度，请回复「确认」继续，回复「取消」放弃。";
            }
            if (ConfirmationRecord.STATUS_PENDING.equals(gate.status())) {
                audit(runId, node, name, "DENIED", 0, "await-confirmation", toolInput);
                return "试穿会消耗生成额度，请回复「确认」继续，回复「取消」放弃。";
            }
            if (ConfirmationRecord.STATUS_REJECTED.equals(gate.status())) {
                return "已取消该操作。";
            }
            if (gate.isConsumed()) {
                String replay = gate.resultSummary();
                return replay == null || replay.isBlank() ? "该操作已完成。" : replay;
            }
        }

        long start = System.nanoTime();
        try {
            String result = invokeWithTimeout(toolInput, toolContext);
            if (gate != null && ConfirmationRecord.STATUS_CONFIRMED.equals(gate.status())) {
                confirmationService.consume(gate, result);
            }
            // 工具可能用「返回失败文案」而非抛异常来表达失败（项目内失败文案统一以「抱歉」开头），
            // 此处如实记为 FAILED，避免管理台把失败调用显示为成功。
            boolean failedByText = looksLikeFailure(result);
            audit(runId, node, name, failedByText ? "FAILURE" : "SUCCESS", elapsedMs(start),
                    failedByText ? "tool-returned-failure-text" : null, toolInput);
            return result;
        } catch (TimeoutException e) {
            log.warn("Tool call timed out after {}ms: {}", policy.timeoutMillis(), name);
            audit(runId, node, name, "TIMEOUT", elapsedMs(start), "timeout", toolInput);
            return "工具调用超时：" + name;
        } catch (RuntimeException e) {
            audit(runId, node, name, "FAILURE", elapsedMs(start), e.getClass().getSimpleName(), toolInput);
            throw e;
        }
    }

    private String invokeWithTimeout(String toolInput, ToolContext toolContext) throws TimeoutException {
        if (policy.timeoutMillis() <= 0 || executor == null) {
            return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        }
        // 关键：工具在独立执行器线程上跑，ThreadLocal(AgentSessionContext/TrajectoryRunContext) 不会自动继承，
        // 必须在提交前捕获、在线程内应用（否则 requireUserId() 会失败、轨迹丢 runId）。
        AgentExecutionContext.Snapshot execCtx = AgentExecutionContext.capture();
        Future<String> future = executor.submit(() -> AgentExecutionContext.callWith(execCtx,
                () -> toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext)));
        try {
            return future.get(policy.timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("tool call interrupted: " + toolInput, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        }
    }

    private void audit(String runId, String node, String toolName, String status,
                       long durationMs, String error, String input) {
        if (auditRecorder == null || runId == null || runId.isBlank()) {
            return;
        }
        auditRecorder.recordStep(new AgentTrajectoryRecorder.Step(
                runId,
                node == null ? "" : node,
                AgentTrajectoryRecorder.Step.TYPE_TOOL,
                status,
                null,
                toolName,
                redactedInput(input),
                status,
                0, 0, 0,
                durationMs,
                error));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 工具以「返回失败文案」表达失败时的识别（项目失败文案统一以「抱歉」开头或含「暂时遇到」）。 */
    static boolean looksLikeFailure(String result) {
        if (result == null) {
            return false;
        }
        String r = result.strip();
        return r.startsWith("抱歉") || r.contains("暂时遇到")
                || r.startsWith("工具调用超时") || r.startsWith("工具调用被拒绝");
    }

    /** 输入脱敏：只保留 sha256 前 16 位 + 长度，避免敏感参数落库。 */
    static String redactedInput(String input) {
        if (input == null) {
            return "null";
        }
        String hash = sha256(input);
        return "sha256=" + hash.substring(0, Math.min(16, hash.length())) + ",len=" + input.length();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
