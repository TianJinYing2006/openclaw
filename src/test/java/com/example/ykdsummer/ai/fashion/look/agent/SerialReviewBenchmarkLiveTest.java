package com.example.ykdsummer.ai.fashion.look.agent;

import com.example.ykdsummer.ai.fashion.look.model.AnalyzedQuery;
import com.example.ykdsummer.ai.fashion.look.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.look.model.FashionResult;
import com.example.ykdsummer.ai.fashion.look.model.StylistOutput;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真实 LLM 链路：Critic/Trend 并行 vs 串行评审实测（临时基准，跑完可删）。
 *
 * <p>用法：<pre>RESUME_LLM_LIVE=true mvn test -Dtest=SerialReviewBenchmarkLiveTest</pre>
 *
 * <p>同一份 Stylist 输出、同一输入，交替跑并行/串行各 3 轮取平均，
 * 消除模型调用波动与时段差异，坐实"时延降低约 50%"的真实口径。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RESUME_LLM_LIVE", matches = "true")
class SerialReviewBenchmarkLiveTest {

    @Autowired
    private AgentCoordinator coordinator;
    @Autowired
    private CriticAgent criticAgent;
    @Autowired
    private TrendAgent trendAgent;

    private ExecutorService executor;

    @BeforeEach
    void setup() {
        AgentSessionContext.set("bench-user", "bench-session");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void teardown() {
        AgentSessionContext.clear();
        executor.shutdownNow();
    }

    @Test
    void serialVsParallelRealBenchmark() {
        // 1) 跑一次完整链路，取同一份 Stylist 输出与 Query（含真实 RAG 上下文）
        FashionResult first = coordinator.process(new FashionRequest("bench-user", "参加婚礼怎么穿"));
        StylistOutput stylist = first.stylist();
        AnalyzedQuery query = first.analyzedQuery();
        System.out.println("[bench] success=" + first.success()
                + " suggestions=" + (stylist.suggestions() == null ? 0 : stylist.suggestions().size()));

        // 2) 交替跑 3 轮，消除时段波动
        List<Long> parallelMs = new ArrayList<>();
        List<Long> serialMs = new ArrayList<>();
        int rounds = 3;
        for (int i = 0; i < rounds; i++) {
            long p = runParallel(stylist, query);
            long s = runSerial(stylist, query);
            parallelMs.add(p);
            serialMs.add(s);
            System.out.printf("[bench] round %d: parallel=%dms serial=%dms%n", i + 1, p, s);
        }

        double pAvg = parallelMs.stream().mapToLong(Long::longValue).average().orElse(0);
        double sAvg = serialMs.stream().mapToLong(Long::longValue).average().orElse(0);
        double reduce = (sAvg - pAvg) / sAvg * 100;
        System.out.printf("[bench] PARALLEL_MS_AVG=%.0f SERIAL_MS_AVG=%.0f REDUCE_PCT=%.1f%n", pAvg, sAvg, reduce);
    }

    private long runParallel(StylistOutput stylist, AnalyzedQuery query) {
        CompletableFuture<?> criticFuture = CompletableFuture.supplyAsync(
                () -> criticAgent.execute(stylist, query), executor);
        CompletableFuture<?> trendFuture = CompletableFuture.supplyAsync(
                () -> trendAgent.execute(stylist, query), executor);
        long start = System.nanoTime();
        criticFuture.join();
        trendFuture.join();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private long runSerial(StylistOutput stylist, AnalyzedQuery query) {
        long start = System.nanoTime();
        criticAgent.execute(stylist, query);
        trendAgent.execute(stylist, query);
        return (System.nanoTime() - start) / 1_000_000;
    }
}
