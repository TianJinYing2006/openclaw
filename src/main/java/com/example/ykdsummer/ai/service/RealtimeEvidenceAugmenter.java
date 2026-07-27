package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.RealtimeEvidenceProperties;
import com.example.ykdsummer.ai.tool.WebSearchTools;
import com.example.ykdsummer.persistence.RedisOperationalStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 将一个已被模型选择的实时查询 Tool 扩展为“两路证据”。
 *
 * <p>专用数据源和联网搜索在受限线程池中并行执行，统一等待至配置的截止时间。任一路失败、
 * 返回空值或超时都不会丢掉另一路证据，最终仍由模型判断如何回答。它只接受白名单中的无状态
 * 只读 Tool，避免破坏图片、文件和写操作依赖的当前请求上下文。</p>
 */
@Component
public class RealtimeEvidenceAugmenter {

    private final RealtimeEvidenceProperties properties;
    private final WebSearchTools webSearchTools;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Cache<String, String> webEvidenceCache;
    private final RedisOperationalStore redis;

    @Autowired
    public RealtimeEvidenceAugmenter(
            RealtimeEvidenceProperties properties,
            WebSearchTools webSearchTools,
            ObjectMapper objectMapper,
            ObjectProvider<RedisOperationalStore> redisProvider
    ) {
        this(properties, webSearchTools, objectMapper, newExecutor(properties), redisProvider.getIfAvailable());
    }

    RealtimeEvidenceAugmenter(
            RealtimeEvidenceProperties properties,
            WebSearchTools webSearchTools,
            ObjectMapper objectMapper,
            ExecutorService executor
    ) {
        this(properties, webSearchTools, objectMapper, executor, null);
    }

    RealtimeEvidenceAugmenter(
            RealtimeEvidenceProperties properties,
            WebSearchTools webSearchTools,
            ObjectMapper objectMapper,
            ExecutorService executor,
            RedisOperationalStore redis
    ) {
        this.properties = properties;
        this.webSearchTools = webSearchTools;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.redis = redis;
        this.webEvidenceCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCacheEntries())
                .expireAfterWrite(properties.getCacheTtl())
                .build();
    }

    public String augment(String toolName, String toolInput, Supplier<String> specializedCall) {
        if (!properties.isEnabled() || !properties.isEligible(toolName)) {
            return specializedCall.get();
        }

        String query = buildSearchQuery(toolName, toolInput);
        CompletableFuture<Evidence> specialized = submit(
                () -> limit(specializedCall.get(), properties.getMaxSpecializedResultCharacters()), "专用实时工具");
        CompletableFuture<Evidence> web = submit(() -> webEvidence(query), "联网辅助检索");
        Instant deadline = Instant.now().plus(properties.getTimeout());
        Evidence specializedEvidence = await(specialized, deadline, "专用实时工具");
        Evidence webEvidence = await(web, deadline, "联网辅助检索");
        return format(toolName, specializedEvidence, webEvidence);
    }

    private CompletableFuture<Evidence> submit(Supplier<String> operation, String source) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return Evidence.value(operation.get(), source);
                } catch (RuntimeException exception) {
                    return Evidence.failure(source + "执行失败：" + exception.getClass().getSimpleName());
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.completedFuture(Evidence.failure(source + "队列繁忙，未执行"));
        }
    }

    private Evidence await(CompletableFuture<Evidence> future, Instant deadline, String source) {
        // 另一条路径耗尽等待时间后，本路径可能已经完成；先取回它，不能误标为超时。
        if (future.isDone()) {
            return completed(future, source);
        }
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            future.cancel(true);
            return Evidence.failure(source + "超时，已到达统一等待上限");
        }
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return Evidence.failure(source + "超时，已到达统一等待上限");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Evidence.failure(source + "等待被中断");
        } catch (ExecutionException exception) {
            return Evidence.failure(source + "执行失败：" + exception.getCause().getClass().getSimpleName());
        }
    }

    private Evidence completed(CompletableFuture<Evidence> future, String source) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Evidence.failure(source + "等待被中断");
        } catch (ExecutionException exception) {
            return Evidence.failure(source + "执行失败：" + exception.getCause().getClass().getSimpleName());
        } catch (CancellationException exception) {
            return Evidence.failure(source + "超时，已到达统一等待上限");
        }
    }

    private String webEvidence(String query) {
        String shared = redis == null ? null : redis.get("realtime-web", query);
        if (shared != null) return limit(shared, properties.getMaxWebResultCharacters());
        return webEvidenceCache.get(query, ignored -> {
            String evidence = limit(webSearchTools.webSearch(query), properties.getMaxWebResultCharacters());
            if (redis != null) redis.put("realtime-web", query, evidence, properties.getCacheTtl());
            return evidence;
        });
    }

    private String buildSearchQuery(String toolName, String toolInput) {
        List<String> values = new ArrayList<>();
        try {
            collectText(objectMapper.readTree(toolInput == null ? "{}" : toolInput), values);
        } catch (Exception ignored) {
            addValue(values, toolInput);
        }
        String query = "实时查询 " + toolName + " " + String.join(" ", values);
        return query.length() <= properties.getMaxQueryCharacters()
                ? query.strip()
                : query.substring(0, properties.getMaxQueryCharacters()).strip();
    }

    private static void collectText(JsonNode node, List<String> values) {
        if (node == null || node.isNull() || values.size() >= 12) {
            return;
        }
        if (node.isValueNode()) {
            addValue(values, node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectText(item, values));
            return;
        }
        node.elements().forEachRemaining(item -> collectText(item, values));
    }

    private static void addValue(List<String> values, String value) {
        if (value == null || value.isBlank() || values.size() >= 12) {
            return;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (!normalized.isBlank()) {
            values.add(normalized.length() <= 100 ? normalized : normalized.substring(0, 100));
        }
    }

    private String format(String toolName, Evidence specialized, Evidence web) {
        String result = "【专用实时工具结果：" + toolName + "】\n" + specialized.text()
                + "\n\n【联网辅助检索：仅作补充和交叉验证】\n" + web.text()
                + "\n\n请优先采用专用工具的结构化结果；若其失败、为空或与联网资料冲突，"
                + "请明确说明不确定性，不要编造。";
        return limit(result, properties.getMaxCombinedResultCharacters());
    }

    private static String limit(String value, int maxCharacters) {
        String safe = value == null ? "" : value.strip();
        if (safe.codePointCount(0, safe.length()) <= maxCharacters) {
            return safe;
        }
        int end = safe.offsetByCodePoints(0, maxCharacters);
        return safe.substring(0, end) + "\n[工具结果已截断，必要时请继续查询更具体的条件]";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ExecutorService newExecutor(RealtimeEvidenceProperties properties) {
        AtomicLong sequence = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "realtime-evidence-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                properties.getWorkerThreads(),
                properties.getWorkerThreads(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private record Evidence(String text) {
        static Evidence value(String value, String source) {
            return new Evidence(value == null || value.isBlank() ? source + "没有返回有效数据" : value.strip());
        }
        static Evidence failure(String value) { return new Evidence(value); }
    }
}
