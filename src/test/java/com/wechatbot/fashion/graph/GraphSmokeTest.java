package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1 验证：spring-ai-alibaba-graph 能否在本项目（Spring AI 1.1.8 / Spring Boot 3.5.16 / Java 21）
 * 下编译运行。仅验证框架集成，不涉及真实 LLM / RAG。
 *
 * 若本测试编译或运行失败，说明 graph-core 1.1.2.3 与 Spring AI 1.1.8 存在 API 漂移，
 * 需回退 Option B（把 spring-ai.version 降到 1.1.2 对齐 graph-core）。
 */
class GraphSmokeTest {

    @Test
    void buildsAndRunsSimpleGraph() throws Exception {
        StateGraph graph = new StateGraph();

        AsyncNodeAction nodeA = (OverAllState s) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("a", "hello");
            return CompletableFuture.completedFuture(m);
        };
        AsyncNodeAction nodeB = (OverAllState s) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("b", "world");
            return CompletableFuture.completedFuture(m);
        };

        graph.addNode("nodeA", nodeA);
        graph.addNode("nodeB", nodeB);
        graph.addEdge(StateGraph.START, "nodeA");
        graph.addEdge("nodeA", "nodeB");
        graph.addEdge("nodeB", StateGraph.END);

        // 默认内存 saver（compile() 无参）。Redis saver 在阶段 2 接。
        CompiledGraph compiled = graph.compile();

        Map<String, Object> initial = new HashMap<>();
        initial.put("input", "x");

        Optional<OverAllState> result = compiled.invoke(
                initial,
                RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build());

        assertTrue(result.isPresent(), "graph 应返回最终 state");
        assertEquals("world", result.get().value("b").orElse(null));
        assertFalse(result.get().value("a").isEmpty(), "nodeA 写入的 state 应保留");
    }
}
