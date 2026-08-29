package com.wechatbot.fashion.ai.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

class BoundedToolCallingManagerTest {

    @Test
    void permitsFourToolPlanningRoundsThenStopsBeforeExecutingTheFifth() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(mock(ToolExecutionResult.class));
        AiProperties properties = new AiProperties();
        properties.setMaxAgentRounds(4);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, properties);
        Prompt prompt = mock(Prompt.class);
        ChatResponse response = mock(ChatResponse.class);

        for (int round = 0; round < 4; round++) {
            manager.executeToolCalls(prompt, response);
        }

        assertThatThrownBy(() -> manager.executeToolCalls(prompt, response))
                .isInstanceOf(AgentRoundLimitExceededException.class)
                .hasMessageContaining("4");
        verify(delegate, times(4)).executeToolCalls(prompt, response);

        manager.clearRequest();
        manager.executeToolCalls(prompt, response);
        verify(delegate, times(5)).executeToolCalls(prompt, response);
    }
}
