package com.example.ykdsummer.ai.orchestration;

import com.example.ykdsummer.ai.config.AiProperties;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Limits Agent planning rounds, not the number of tools in one model response.
 * Spring AI invokes {@link #executeToolCalls(Prompt, ChatResponse)} once after each model response that
 * contains one or more tool calls, so this is the correct boundary for a round limit.
 */
public final class BoundedToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate;
    private final AiProperties properties;
    private final ThreadLocal<Integer> rounds = ThreadLocal.withInitial(() -> 0);

    public BoundedToolCallingManager(ToolCallingManager delegate, AiProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        int nextRound = rounds.get() + 1;
        int maxRounds = properties.getMaxAgentRounds();
        if (nextRound > maxRounds) {
            throw new AgentRoundLimitExceededException(maxRounds);
        }
        rounds.set(nextRound);
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    /** The chat gateway calls this in finally so pooled worker threads never inherit a prior request's count. */
    public void clearRequest() {
        rounds.remove();
    }
}
