package com.wechatbot.fashion.graph;

import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

/**
 * Langfuse 观测链路冒烟（评审证据：可观测性 / token 成本核销前导）。
 *
 * <p>以 OTel 导出启用状态跑 2 条真实 query（一复杂一简单），验证：
 * <ol>
 *   <li>trace 进入 Langfuse（jp.cloud.langfuse.com）；</li>
 *   <li>每个 Agent LLM 调用自动成为 GENERATION 子 span 且带 token usage
 *      （Spring AI ChatModel 观测 + 本项目的 ChatModelCompletionContentObservationFilter）。</li>
 * </ol>
 * 成功后用 {@code scripts/langfuse_cost_audit.py} 聚合 token 成本。
 *
 * <p>运行（带 OTLP 导出环境变量）：
 * <pre>
 *   LANGFUSE_OTLP_ENABLED=true \
 *   LANGFUSE_OTLP_ENDPOINT=https://jp.cloud.langfuse.com/api/public/otel \
 *   LANGFUSE_OTLP_HEADERS="Authorization=Bearer pk-lf-...:sk-lf-..." \
 *   RESUME_LANGFUSE_SMOKE=true mvn test -Dtest=LangfuseTraceSmokeLiveTest
 * </pre>
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=true",
                "app.fashion.semantic.enabled=false",
                "app.fashion.rag.provider=${RESUME_STUDY_RAG_PROVIDER:ragflow}",
                "app.fashion.rag.diversity.enabled=false",
                "app.fashion.reference.enabled=false",
                "app.fashion.outfit-recommendation.enabled=false",
                "app.fashion.graph.enabled=true",
                "app.fashion.graph.shadow=true",
                "app.fashion.graph.tool-loop.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "RESUME_LANGFUSE_SMOKE", matches = "true")
class LangfuseTraceSmokeLiveTest {

    private static final String EVAL_USER = "langfuse-smoke";

    @Autowired(required = false)
    private FashionGraphRunner graphRunner;

    @BeforeEach
    void bind() {
        AgentSessionContext.set(EVAL_USER, "langfuse-smoke-session");
    }

    @AfterEach
    void clear() {
        AgentSessionContext.clear();
    }

    @Test
    void runTwoRealQueriesForObservability() {
        if (graphRunner == null) {
            throw new IllegalStateException("graphRunner 未装配：需 app.fashion.graph.enabled=true");
        }
        List<String> queries = List.of(
                "杭州33度高温，去见对象，我比较喜欢黑色的衣服，帮我推荐一套",
                "帮我推荐一套日常上班的简约穿搭");
        for (int i = 0; i < queries.size(); i++) {
            long t0 = System.currentTimeMillis();
            Optional<FashionResult> result = graphRunner.runForResult(
                    new FashionRequest(EVAL_USER, queries.get(i)), "langfuse-smoke-" + System.nanoTime());
            long elapsed = System.currentTimeMillis() - t0;
            FashionResult r = result.orElse(null);
            System.out.println("[langfuse-smoke] q" + i + " present=" + result.isPresent()
                    + " degraded=" + (r == null ? null : r.degraded())
                    + " elapsed=" + elapsed + "ms");
            System.out.println("[langfuse-smoke] q" + i + " query=" + queries.get(i));
        }
        System.out.println("[langfuse-smoke] 完成，请到 Langfuse 界面核对 trace，或跑 scripts/langfuse_cost_audit.py 聚合");
    }
}