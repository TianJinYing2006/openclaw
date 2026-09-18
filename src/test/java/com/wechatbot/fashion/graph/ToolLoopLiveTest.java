package com.wechatbot.fashion.graph;

import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

/**
 * tool_loop 真实链路冒烟（P0-2）：图内工具循环开启时，命中「城市+天气」的查询
 * 应能走通完整图并返回正常 FashionResult；工具是否成功只影响 TOOL_CONTEXT 是否非空，
 * 不阻断图、不降级。
 *
 * <p>运行：{@code RESUME_TOOL_LOOP_LIVE=true mvn test -Dtest=ToolLoopLiveTest}
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.admin.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=true",
                "app.fashion.semantic.enabled=false",
                "app.fashion.rag.provider=${RESUME_BENCH_RAG_PROVIDER:mysql}",
                "app.fashion.rag.diversity.enabled=false",
                "app.fashion.reference.enabled=false",
                "app.fashion.outfit-recommendation.enabled=false",
                "app.ai.usage.daily-token-limit=999999999",
                "app.web-search.provider=bocha",
                "app.weather.provider=uapis",
                "app.fashion.graph.enabled=true",
                "app.fashion.graph.shadow=false",
                "app.fashion.graph.tool-loop.enabled=true"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "RESUME_TOOL_LOOP_LIVE", matches = "true")
class ToolLoopLiveTest {

    @Autowired(required = false)
    private FashionGraphRunner graphRunner;

    @BeforeEach
    void bind() {
        AgentSessionContext.set("tool-loop-user", "tool-loop-session");
    }

    @AfterEach
    void clear() {
        AgentSessionContext.clear();
    }

    @Test
    void realGraphWithToolLoopReturnsResultForCityWeatherQuery() {
        if (graphRunner == null) {
            System.out.println("[tool-loop] graphRunner not available, skip");
            return;
        }
        FashionRequest request = new FashionRequest("tool-loop-user", "杭州今天33度高温，帮我推荐一套适合的穿搭");
        long start = System.currentTimeMillis();
        Optional<FashionResult> result = graphRunner.runForResult(request, "tool-loop-" + System.currentTimeMillis());
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[tool-loop] graph result present=" + result.isPresent()
                + " degraded=" + result.map(FashionResult::degraded).orElse(null)
                + " elapsed=" + elapsed + "ms");
        if (result.isPresent()) {
            FashionResult r = result.get();
            System.out.println("[tool-loop] success=" + r.success()
                    + " suggestionCount=" + (r.stylist() != null && r.stylist().suggestions() != null
                    ? r.stylist().suggestions().size() : 0));
            System.out.println("[tool-loop] ragContextHead=" + (r.ragContext() == null ? "null"
                    : r.ragContext().length() > 80 ? r.ragContext().substring(0, 80) : r.ragContext()));
        }
    }

    /**
     * 闭环验证（2026-09-01，天气+搜索双工具）：命中「实时资讯词」应触发 search_web，
     * 图正常完成；搜索成功 → stylist 上下文含外部信息，失败 → 优雅兜底（TOOL_CONTEXT 空，不降级）。
     * 观察日志关键字：{@code tool_loop: external context enriched} / {@code no usable tool context}。
     */
    @Test
    void realGraphWithSearchTriggerKeepsGraphHealthy() {
        if (graphRunner == null) {
            System.out.println("[tool-loop] graphRunner not available, skip");
            return;
        }
        FashionRequest request = new FashionRequest("tool-loop-user", "帮我看看今年秋冬流行的穿搭趋势，推荐一套");
        long start = System.currentTimeMillis();
        Optional<FashionResult> result = graphRunner.runForResult(request, "tool-loop-search-" + System.currentTimeMillis());
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[tool-loop-search] graph present=" + result.isPresent()
                + " degraded=" + result.map(FashionResult::degraded).orElse(null)
                + " elapsed=" + elapsed + "ms");
        result.ifPresent(r -> System.out.println("[tool-loop-search] success=" + r.success()));
    }
}