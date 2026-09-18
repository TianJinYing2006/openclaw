package com.wechatbot.fashion.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 穿搭 Agent 单次运行的执行预算（护栏）：模型调用次数 / Token 总量 / 总 deadline / 评审节点 deadline。
 *
 * <p>默认关闭（{@code enabled=false}），避免改变既有线上口径；开启后由
 * {@code RunBudgetTracker} 在每次 run 内累计并拒绝超预算的模型调用，节点据此降级。
 */
@ConfigurationProperties(prefix = "app.fashion.graph.budget")
public class AgentBudgetProperties {

    /** 是否启用执行预算护栏。 */
    private boolean enabled = false;
    /** 单次 run 允许的最大模型调用次数。 */
    private int maxModelCalls = 12;
    /** 单次 run 允许的最大工具调用次数（全局，叠加各工具的 maxCallsPerRun）。 */
    private int maxToolCallsPerRun = 30;
    /** 单次 run 允许的最大 Token 总量（prompt+completion）。 */
    private long maxTotalTokens = 30_000L;
    /** 单次 run 的总墙钟 deadline。 */
    private Duration runDeadline = Duration.ofSeconds(60);
    /** Critic 节点（Critic ∥ Trend 并行）的节点级 deadline。 */
    private Duration criticDeadline = Duration.ofSeconds(20);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls > 0 ? maxModelCalls : 1; }
    public int getMaxToolCallsPerRun() { return maxToolCallsPerRun; }
    public void setMaxToolCallsPerRun(int maxToolCallsPerRun) {
        this.maxToolCallsPerRun = maxToolCallsPerRun > 0 ? maxToolCallsPerRun : 1;
    }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(long maxTotalTokens) { this.maxTotalTokens = maxTotalTokens > 0 ? maxTotalTokens : 1; }
    public Duration getRunDeadline() { return runDeadline; }
    public void setRunDeadline(Duration runDeadline) {
        this.runDeadline = runDeadline == null || runDeadline.isZero() || runDeadline.isNegative()
                ? Duration.ofSeconds(60) : runDeadline;
    }
    public Duration getCriticDeadline() { return criticDeadline; }
    public void setCriticDeadline(Duration criticDeadline) {
        this.criticDeadline = criticDeadline == null || criticDeadline.isZero() || criticDeadline.isNegative()
                ? Duration.ofSeconds(20) : criticDeadline;
    }
}
