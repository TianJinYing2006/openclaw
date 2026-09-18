package com.wechatbot.fashion.graph.plan;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.graph.FashionResultBuilders;
import com.wechatbot.fashion.graph.nodes.ToolLoopNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 由 {@link AnalyzedQuery} + 原始 query 构造 {@link ExecutionPlan}（确定性，不额外调用 LLM）。
 *
 * <p>计划步骤与图拓扑一致：retrieve_memory → rag →[tool]→ stylist →[critic]→ responder。
 * 是否包含 tool/critic 复用既有确定性判定（{@link ToolLoopNode#needsTool} / {@link FashionResultBuilders#isSimpleRequest}），
 * 使计划与执行不会漂移。
 */
public final class PlanBuilder {

    private static final String TASK_TYPE = "OUTFIT_RECOMMENDATION";

    /** 避免颜色抽取：匹配「不要/别穿/不喜欢 + 颜色词」。 */
    private static final Pattern AVOID_COLOR = Pattern.compile(
            "(?:不要|别穿|不喜欢|不想穿)([\\u4e00-\\u9fa5]{1,3}色?)");

    private PlanBuilder() {
    }

    public static ExecutionPlan from(AnalyzedQuery analyzed, String rawQuery) {
        boolean simple = FashionResultBuilders.isSimpleRequest(analyzed);
        boolean needsTool = ToolLoopNode.needsTool(rawQuery);

        List<ExecutionPlan.PlanStep> steps = new ArrayList<>();
        steps.add(new ExecutionPlan.PlanStep("retrieve", "retrieve_memory", true));
        steps.add(new ExecutionPlan.PlanStep("rag", "rag", true));
        if (needsTool) {
            steps.add(new ExecutionPlan.PlanStep("tool", ExecutionPlan.KIND_TOOL, false));
        }
        steps.add(new ExecutionPlan.PlanStep("stylist", "stylist", true));
        if (!simple) {
            steps.add(new ExecutionPlan.PlanStep("critic", "critic", false));
        }
        steps.add(new ExecutionPlan.PlanStep("responder", "responder", true));

        String scene = analyzed != null && analyzed.params() != null ? analyzed.params().scene() : "DAILY";
        int formality = analyzed != null && analyzed.params() != null ? analyzed.params().formality() : 2;
        return new ExecutionPlan(TASK_TYPE, List.copyOf(steps),
                new ExecutionPlan.PlanConstraints(scene, formality, extractAvoidColors(rawQuery)),
                steps.size());
    }

    static List<String> extractAvoidColors(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        List<String> colors = new ArrayList<>();
        Matcher matcher = AVOID_COLOR.matcher(rawQuery);
        while (matcher.find() && colors.size() < 5) {
            String color = matcher.group(1);
            if (!colors.contains(color)) {
                colors.add(color);
            }
        }
        return List.copyOf(colors);
    }
}
