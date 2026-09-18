package com.wechatbot.fashion.ai.fashion.look.agent;

import com.wechatbot.fashion.ai.config.AgentBudgetProperties;
import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.graph.budget.RunBudgetTracker;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 预算护栏在模型调用层的执行：超预算后不再调用底层模型。 */
class AgentLlmCallerBudgetTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void shutdown() {
        EXECUTOR.shutdown();
    }

    @AfterEach
    void clearContext() {
        TrajectoryRunContext.clear();
    }

    @Test
    void stopsCallingModelAfterBudgetExhausted() {
        ChatModel model = mock(ChatModel.class);
        // 第一次调用抛非网络异常：消耗唯一额度且返回 null；第二次应被预算拦截，不再触达模型。
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));

        AgentLlmCaller caller = new AgentLlmCaller(model, new AiProperties(), EXECUTOR);
        AgentBudgetProperties props = new AgentBudgetProperties();
        props.setEnabled(true);
        props.setMaxModelCalls(1);
        RunBudgetTracker tracker = new RunBudgetTracker(props);
        tracker.begin("run-budget-1");
        caller.setRunBudgetTracker(tracker);

        TrajectoryRunContext.set("run-budget-1", "planner");
        Object first = caller.callAgent("sys", "user", Map.class, 100, Duration.ofSeconds(2));
        Object second = caller.callAgent("sys", "user", Map.class, 100, Duration.ofSeconds(2));

        assertThat(first).isNull();
        assertThat(second).isNull();
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(tracker.exceededReason("run-budget-1")).contains("maxModelCalls");
    }
}
