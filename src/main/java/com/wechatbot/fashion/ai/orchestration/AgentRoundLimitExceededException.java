package com.wechatbot.fashion.ai.orchestration;

/** Raised before executing a tool round that would exceed the configured Agent planning budget. */
public class AgentRoundLimitExceededException extends RuntimeException {
    private final int maxRounds;

    public AgentRoundLimitExceededException(int maxRounds) {
        super("Agent tool-call round limit exceeded: " + maxRounds);
        this.maxRounds = maxRounds;
    }

    public int maxRounds() { return maxRounds; }
}
