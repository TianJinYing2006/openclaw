package com.wechatbot.fashion.ai.orchestration;

import com.wechatbot.fashion.ai.config.AiProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
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
    /** 本次请求实际调用过的工具名集合，供网关判断模型是否漏调了穿搭推荐工具。 */
    private final ThreadLocal<Set<String>> calledTools = ThreadLocal.withInitial(HashSet::new);

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
        recordCalledTools(chatResponse);
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    /** 从模型本轮 tool_calls 中记录实际请求的工具名。 */
    private void recordCalledTools(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || !(chatResponse.getResult().getOutput() instanceof AssistantMessage assistant)) {
            return;
        }
        List<?> calls = assistant.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        Set<String> names = calledTools.get();
        for (Object call : calls) {
            if (call instanceof AssistantMessage.ToolCall toolCall && toolCall.name() != null) {
                names.add(toolCall.name());
            }
        }
    }

    /** 本次请求已调用过的工具名集合（只读快照）。 */
    public Set<String> calledToolNames() {
        return Set.copyOf(calledTools.get());
    }

    /** The chat gateway calls this in finally so pooled worker threads never inherit a prior request's count. */
    public void clearRequest() {
        rounds.remove();
        calledTools.remove();
    }
}
