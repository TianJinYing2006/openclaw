package com.wechatbot.fashion.ai.fashion.look.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class FashionAgentExecutorConfigTest {

    @Test
    void shutdownStopsBothManagedPools() {
        FashionAgentExecutorConfig config = new FashionAgentExecutorConfig();
        ExecutorService parallel = config.fashionAgentParallelExecutor();
        ExecutorService llmCaller = config.agentLlmCallerExecutor();

        assertThat(parallel.isShutdown()).isFalse();
        assertThat(llmCaller.isShutdown()).isFalse();

        config.shutdown();

        assertThat(parallel.isShutdown()).isTrue();
        assertThat(llmCaller.isShutdown()).isTrue();
    }
}
