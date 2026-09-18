package com.wechatbot.fashion.graph.plan;

import java.util.List;

/**
 * Planner 输出的结构化执行计划：把「分析结果」升级为**可被后续节点消费的计划**。
 *
 * <p>动机（3.3）：原 planner 只输出 {@code AnalyzedQuery}，后续是否调用工具/评审仍由节点各自
 * 用固定规则决定，计划没有真正驱动执行。本记录把子任务、约束、步数上限显式化：
 * <ul>
 *   <li>{@code subTasks}：预计要走的步骤（retrieve/rag/tool/stylist/critic/responder）；</li>
 *   <li>{@code constraints}：场景/正式度/避免颜色；</li>
 *   <li>{@code maxSteps}：Guardrails 的步数上限参考。</li>
 * </ul>
 *
 * @param taskType  任务类型（当前固定 OUTFIT_RECOMMENDATION）
 * @param subTasks  计划的子任务序列
 * @param constraints 约束
 * @param maxSteps  最大步数
 */
public record ExecutionPlan(String taskType, List<PlanStep> subTasks, PlanConstraints constraints, int maxSteps) {

    /** 单个计划步骤。kind 与图节点名对齐（retrieve_memory/rag/tool/stylist/critic/responder）。 */
    public record PlanStep(String id, String kind, boolean required) {
    }

    public record PlanConstraints(String scene, int formality, List<String> avoidColors) {
    }

    public static final String KIND_TOOL = "tool";

    /** 是否需要工具步骤（供 ToolLoopNode 消费）。 */
    public boolean requiresTool() {
        return subTasks != null && subTasks.stream().anyMatch(step -> KIND_TOOL.equals(step.kind()));
    }

    public List<String> plannedNodeKinds() {
        return subTasks == null ? List.of() : subTasks.stream().map(PlanStep::kind).toList();
    }
}
