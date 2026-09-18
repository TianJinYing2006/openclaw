package com.wechatbot.fashion.ai.orchestration;

/**
 * 单个工具的治理策略：风险等级 + 输入上限 + 是否需确认 + 超时 + 单 run 调用上限。
 *
 * @param risk                 风险等级
 * @param maxInputChars        工具参数 JSON 字符上限；{@code 0} 表示不显式限制
 * @param requiresConfirmation 破坏性/高费用操作（标记 + 审计；完整确认流属 HITL）
 * @param timeoutMillis        单次调用超时；{@code 0} 表示用风险等级默认值
 * @param maxCallsPerRun       单次 run 内该工具的调用上限；{@code 0} 表示不显式限制（仍受全局上限约束）
 */
public record ToolPolicy(ToolRisk risk, int maxInputChars, boolean requiresConfirmation,
                         long timeoutMillis, int maxCallsPerRun) {

    /** 兼容旧 3 参数构造。 */
    public ToolPolicy(ToolRisk risk, int maxInputChars, boolean requiresConfirmation) {
        this(risk, maxInputChars, requiresConfirmation, 0, 0);
    }

    /** 默认策略：只读、不限制输入、无需确认、用风险默认超时/调用上限。 */
    public static ToolPolicy readOnly() {
        return new ToolPolicy(ToolRisk.READ_ONLY, 0, false, 0, 0);
    }

    public static ToolPolicy of(ToolRisk risk) {
        return new ToolPolicy(risk, 0, false, 0, 0);
    }

    public static ToolPolicy limited(ToolRisk risk, int maxInputChars) {
        return new ToolPolicy(risk, maxInputChars, false, 0, 0);
    }

    public static ToolPolicy confirm(ToolRisk risk) {
        return new ToolPolicy(risk, 0, true, 0, 0);
    }

    /** 补全：timeout/maxCalls 为 0 时用风险等级默认值填充（返回新实例，不修改原对象）。 */
    public ToolPolicy withRiskDefaults(long defaultTimeoutMillis, int defaultMaxCallsPerRun) {
        return new ToolPolicy(
                risk,
                maxInputChars,
                requiresConfirmation,
                timeoutMillis > 0 ? timeoutMillis : defaultTimeoutMillis,
                maxCallsPerRun > 0 ? maxCallsPerRun : defaultMaxCallsPerRun);
    }
}
