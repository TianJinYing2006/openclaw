package com.wechatbot.fashion.graph.plan;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Planner 结构化计划：步骤/约束/工具判定/避免颜色抽取。 */
class PlanBuilderTest {

    @Test
    void formalRequestPlansCriticStep() {
        String query = "我要去海边婚礼穿什么，帮我搭一套正式的礼服";
        ExecutionPlan plan = PlanBuilder.from(AnalyzedQuery.fallback(query), query);

        assertThat(plan.taskType()).isEqualTo("OUTFIT_RECOMMENDATION");
        assertThat(plan.plannedNodeKinds())
                .containsExactly("retrieve_memory", "rag", "stylist", "critic", "responder");
        assertThat(plan.constraints().formality()).isEqualTo(4);
        assertThat(plan.maxSteps()).isEqualTo(plan.subTasks().size());
    }

    @Test
    void simpleRequestSkipsCriticStep() {
        String query = "周末逛街穿什么";
        ExecutionPlan plan = PlanBuilder.from(AnalyzedQuery.fallback(query), query);

        assertThat(plan.plannedNodeKinds()).doesNotContain("critic");
        assertThat(plan.plannedNodeKinds()).contains("stylist", "responder");
    }

    @Test
    void weatherIntentPlansToolStep() {
        String query = "杭州今天天气很热，穿什么合适";
        ExecutionPlan plan = PlanBuilder.from(AnalyzedQuery.fallback(query), query);

        assertThat(plan.requiresTool()).isTrue();
        assertThat(plan.plannedNodeKinds()).contains("tool");
    }

    @Test
    void extractsAvoidColors() {
        assertThat(PlanBuilder.extractAvoidColors("这次不要红色，太正式了")).contains("红色");
        assertThat(PlanBuilder.extractAvoidColors("别穿绿色")).contains("绿色");
        assertThat(PlanBuilder.extractAvoidColors("随便穿点什么")).isEmpty();
    }
}
