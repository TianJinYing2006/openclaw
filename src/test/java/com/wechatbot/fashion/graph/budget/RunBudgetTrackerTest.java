package com.wechatbot.fashion.graph.budget;

import com.wechatbot.fashion.ai.config.AgentBudgetProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 执行预算护栏守门测试：调用次数 / Token 上限 / deadline / 关闭放行 / 结束清理。 */
class RunBudgetTrackerTest {

    private static AgentBudgetProperties enabled(int maxCalls, long maxTokens, Duration deadline) {
        AgentBudgetProperties p = new AgentBudgetProperties();
        p.setEnabled(true);
        p.setMaxModelCalls(maxCalls);
        p.setMaxTotalTokens(maxTokens);
        p.setRunDeadline(deadline);
        return p;
    }

    @Test
    void blocksAfterMaxModelCalls() {
        RunBudgetTracker tracker = new RunBudgetTracker(enabled(2, 1000, Duration.ofMinutes(5)));
        tracker.begin("run-1");

        assertThat(tracker.allowModelCall("run-1")).isTrue();
        assertThat(tracker.allowModelCall("run-1")).isTrue();
        assertThat(tracker.allowModelCall("run-1")).isFalse();
        assertThat(tracker.exceededReason("run-1")).contains("maxModelCalls");
    }

    @Test
    void blocksAfterTokenLimit() {
        RunBudgetTracker tracker = new RunBudgetTracker(enabled(10, 100, Duration.ofMinutes(5)));
        tracker.begin("run-2");

        tracker.addTokens("run-2", 60);
        assertThat(tracker.allowModelCall("run-2")).isTrue();
        tracker.addTokens("run-2", 60);

        assertThat(tracker.allowModelCall("run-2")).isFalse();
        assertThat(tracker.exceededReason("run-2")).contains("maxTotalTokens");
    }

    @Test
    void blocksAfterRunDeadline() {
        RunBudgetTracker tracker = new RunBudgetTracker(enabled(10, 1000, Duration.ofMillis(1)));
        tracker.begin("run-3");
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(tracker.allowModelCall("run-3")).isFalse();
        assertThat(tracker.exceededReason("run-3")).contains("deadline");
    }

    @Test
    void blocksToolCallsAfterGlobalToolBudget() {
        AgentBudgetProperties p = enabled(10, 1000, Duration.ofMinutes(5));
        p.setMaxToolCallsPerRun(2);
        RunBudgetTracker tracker = new RunBudgetTracker(p);
        tracker.begin("run-t");

        assertThat(tracker.allowToolCall("run-t", "get_current_weather")).isTrue();
        assertThat(tracker.allowToolCall("run-t", "get_current_weather")).isTrue();
        assertThat(tracker.allowToolCall("run-t", "get_current_china_time")).isFalse();
        assertThat(tracker.exceededReason("run-t")).contains("maxToolCallsPerRun");
    }

    @Test
    void blocksToolAfterPerToolBudget() {
        AgentBudgetProperties p = enabled(10, 1000, Duration.ofMinutes(5));
        p.setMaxToolCallsPerRun(100);
        RunBudgetTracker tracker = new RunBudgetTracker(p);
        tracker.begin("run-p");

        // generate_image 风险策略默认 maxCallsPerRun=5
        for (int i = 0; i < 5; i++) {
            assertThat(tracker.allowToolCall("run-p", "generate_image")).isTrue();
        }
        assertThat(tracker.allowToolCall("run-p", "generate_image")).isFalse();
        assertThat(tracker.exceededReason("run-p")).contains("toolBudget:generate_image");
    }

    @Test
    void disabledTrackerAlwaysAllows() {
        AgentBudgetProperties disabled = new AgentBudgetProperties(); // enabled=false
        RunBudgetTracker tracker = new RunBudgetTracker(disabled);
        tracker.begin("run-4");
        for (int i = 0; i < 100; i++) {
            assertThat(tracker.allowModelCall("run-4")).isTrue();
        }
        assertThat(tracker.exceededReason("run-4")).isEmpty();
    }

    @Test
    void finishClearsBudget() {
        RunBudgetTracker tracker = new RunBudgetTracker(enabled(1, 1000, Duration.ofMinutes(5)));
        tracker.begin("run-5");
        assertThat(tracker.allowModelCall("run-5")).isTrue();
        assertThat(tracker.allowModelCall("run-5")).isFalse();

        tracker.finish("run-5");

        assertThat(tracker.allowModelCall("run-5")).isTrue();
    }
}
