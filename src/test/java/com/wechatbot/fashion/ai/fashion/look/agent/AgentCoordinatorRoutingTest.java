package com.wechatbot.fashion.ai.fashion.look.agent;

import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态编排路由回归：简单请求跳过 Critic/Trend/Coordinator，复杂请求走完整 5 步。
 */
class AgentCoordinatorRoutingTest {

    @Test
    void simpleDailyRequestSkipsReviewSteps() {
        AnalyzedQuery simple = new AnalyzedQuery(
                "今天穿什么",
                List.of("SUMMER 日常", "DAILY"),
                new AnalyzedQuery.QueryParams("DAILY", "SUMMER", 2, "unknown", ""));

        assertThat(AgentCoordinator.isSimpleRequest(simple)).isTrue();
    }

    @Test
    void formalWeddingRequestRunsFullPipeline() {
        AnalyzedQuery complex = new AnalyzedQuery(
                "参加婚礼怎么穿",
                List.of("FORMAL_EVENT 婚礼", "西装", "香槟色", "伴娘服"),
                new AnalyzedQuery.QueryParams("FORMAL_EVENT", "SUMMER", 5, "female", "优雅"));

        assertThat(AgentCoordinator.isSimpleRequest(complex)).isFalse();
    }

    @Test
    void multiSceneRequestIsComplex() {
        AnalyzedQuery multi = new AnalyzedQuery(
                "海边和婚礼各配一套",
                List.of("SUMMER 海边", "OUTDOOR 度假", "FORMAL_EVENT 婚礼", "西装"),
                new AnalyzedQuery.QueryParams("DAILY", "SUMMER", 2, "unknown", ""));

        assertThat(AgentCoordinator.isSimpleRequest(multi)).isFalse();
    }

    @Test
    void nullQueryIsTreatedAsSimple() {
        assertThat(AgentCoordinator.isSimpleRequest(null)).isTrue();
    }

    @Test
    void commuterQueryWithFewSubqueriesIsSimple() {
        AnalyzedQuery commute = new AnalyzedQuery(
                "上班穿什么",
                List.of("WORKPLACE 通勤", "衬衫"),
                new AnalyzedQuery.QueryParams("WORKPLACE", "SPRING", 3, "unknown", "商务"));

        assertThat(AgentCoordinator.isSimpleRequest(commute)).isTrue();
    }
}
