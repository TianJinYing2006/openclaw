package com.example.ykdsummer.ai.fashion.look.agent;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 穿搭多 Agent 管道使用的虚拟线程池，由 Spring 统一管理生命周期。
 *
 * <p>两个池分别供 {@link AgentCoordinator}（Critic/Trend 并行评审、异步 Embedding 回填）
 * 与 {@link AgentLlmCaller}（Agent LLM 调用的超时控制）使用；应用关闭时
 * 通过 {@link #shutdown()} 优雅关闭并输出日志。</p>
 */
@Configuration
public class FashionAgentExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(FashionAgentExecutorConfig.class);

    private final ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService llmCallerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Bean
    public ExecutorService fashionAgentParallelExecutor() {
        return parallelExecutor;
    }

    @Bean
    public ExecutorService agentLlmCallerExecutor() {
        return llmCallerExecutor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down fashion agent executor pools");
        parallelExecutor.shutdown();
        llmCallerExecutor.shutdown();
    }
}
