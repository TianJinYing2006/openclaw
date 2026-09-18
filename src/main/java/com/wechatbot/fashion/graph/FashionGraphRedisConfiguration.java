package com.wechatbot.fashion.graph;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 穿搭子图（spring-ai-alibaba-graph）的 Redis 连接 Bean。
 *
 * <p>{@link FashionGraphRunner} 在 {@code app.fashion.graph.enabled=true} 时作为 Bean 创建，
 * 其构造器需要注入一个 {@link RedissonClient} 用于 Redis checkpoint（RedisSaver）。
 * 但工程里现有的 Redis 配置（{@code PersistenceConfiguration}）只建了 Spring Data Redis 的
 * Lettuce {@code RedisConnectionFactory} / {@code StringRedisTemplate}，并没有 Redisson 客户端，
 * 因此这里单独补一个，且条件与 Runner 完全一致 —— 图未启用时不创建，避免无效连接。</p>
 *
 * <p>使用独立的 Redis 逻辑库（默认 DB1）存放图 checkpoint，避免与 App 自身的 Redis 键
 * （消息去重 / 限流等，默认 DB0）发生冲突。</p>
 *
 * <p><b>懒连接 + 快速失败</b>：Redis 在 README 中是可选依赖。这里开启 Redisson 懒初始化并关闭重试，
 * 使 {@code Redisson.create} 只解析配置、不在启动时联网；真正的连通性检测交给
 * {@link FashionGraphRunner}（不可达则退化为无 checkpoint 运行，穿搭管道照常可用）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.fashion.graph.enabled", havingValue = "true")
public class FashionGraphRedisConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient fashionGraphRedissonClient(
            @Value("${app.persistence.redis.host:127.0.0.1}") String host,
            @Value("${app.persistence.redis.port:6379}") int port,
            @Value("${app.persistence.redis.password:}") String password) {
        Config config = new Config();
        // 不在 create() 时建立连接，避免 Redis 缺失导致应用启动失败。
        config.setLazyInitialization(true);
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(1)
                .setTimeout(1000)
                .setConnectTimeout(1000)
                // 首次命令连不上时立即失败，由 Runner 捕获并跳过 checkpoint，而非长时间阻塞启动。
                .setRetryAttempts(0);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
