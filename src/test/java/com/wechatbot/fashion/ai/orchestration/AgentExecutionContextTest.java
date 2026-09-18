package com.wechatbot.fashion.ai.orchestration;

import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 3.4：显式上下文跨线程传播并在线程复用时恢复，避免 userId/runId 丢失与污染。 */
class AgentExecutionContextTest {

    @AfterEach
    void clearContexts() {
        AgentSessionContext.clear();
        TrajectoryRunContext.clear();
    }

    @Test
    void propagatesContextToWorkerThreadAndRestoresAfter() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentSessionContext.set("user-1", "sess-1", "token-1");
            TrajectoryRunContext.set("run-1", "planner");
            AgentExecutionContext.Snapshot snapshot = AgentExecutionContext.capture();

            Future<String> inside = executor.submit(() -> AgentExecutionContext.callWith(snapshot,
                    () -> AgentSessionContext.currentUserId() + ":" + TrajectoryRunContext.current().runId()));
            assertThat(inside.get()).isEqualTo("user-1:run-1");

            // 同一线程复用：执行后应恢复到调用前的空上下文，不污染下一个任务
            Future<String> after = executor.submit(() ->
                    AgentSessionContext.currentUserId() + ":" + TrajectoryRunContext.current().present());
            assertThat(after.get()).isEqualTo("anonymous:false");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void captureWithoutContextUsesAnonymous() {
        AgentExecutionContext.Snapshot snapshot = AgentExecutionContext.capture();
        assertThat(snapshot.userId()).isEqualTo("anonymous");
        assertThat(snapshot.runId()).isNull();
    }
}
