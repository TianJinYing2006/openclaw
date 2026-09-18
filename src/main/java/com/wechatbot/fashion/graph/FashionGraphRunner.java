package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.wechatbot.fashion.ai.fashion.look.model.FashionRequest;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryLifecycleListener;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Fashion 子图运行器：编译 {@link FashionGraphDefinition} 并接入 Redis checkpoint。
 *
 * <p>仅在 {@code app.fashion.graph.enabled=true} 时作为 Bean 创建（否则 {@code FashionAgentService}
 * 注入为 null，其 {@code resolveResult} 直接以 {@link FashionResultBuilders#safetyFallback} 兜底响应）。
 * Redis 可用时图状态经 {@link RedisSaver} 持久化，重启后用相同 threadId 可恢复；Redis 不可用则自动
 * 退化为无 checkpoint 运行，不影响搭配生成。
 *
 * <p>{@link #runForResult} 是给 {@code FashionAgentService} 的入口：构造初始输入 → 执行 → 取出
 * {@link FashionState#RESULT}（FashionResult）作为整图产出。
 */
@Component
@ConditionalOnProperty(name = "app.fashion.graph.enabled", havingValue = "true")
public class FashionGraphRunner {

    private static final Logger log = LoggerFactory.getLogger(FashionGraphRunner.class);

    private final FashionGraphContext ctx;
    private final RedissonClient redissonClient;
    private volatile CompiledGraph compiledGraph;
    /** Agent 轨迹记录器；Spring 存在时通过 setter 注入并重编译挂载生命周期监听，测试直接 new 时保持 noop。 */
    private volatile AgentTrajectoryRecorder trajectoryRecorder = AgentTrajectoryRecorder.noop();
    /** 单次 run 执行预算跟踪器；注入后挂到生命周期监听（begin/finish）。 */
    private volatile com.wechatbot.fashion.graph.budget.RunBudgetTracker budgetTracker;

    @Autowired
    public FashionGraphRunner(FashionGraphContext ctx, RedissonClient redissonClient) throws Exception {
        this.ctx = ctx;
        this.redissonClient = redissonClient;
        this.compiledGraph = compile(trajectoryRecorder);
    }

    /** 注入轨迹记录器后重编译图，挂载 {@link TrajectoryLifecycleListener}。 */
    @Autowired(required = false)
    public void setTrajectoryRecorder(AgentTrajectoryRecorder recorder) {
        if (recorder == null) {
            return;
        }
        this.trajectoryRecorder = recorder;
        try {
            this.compiledGraph = compile(recorder);
        } catch (Exception e) {
            log.warn("fashion-graph: 挂载轨迹监听失败，退回无轨迹模式: {}", e.getMessage());
        }
    }

    /** 注入执行预算跟踪器后重编译，挂载 begin/finish。 */
    @Autowired(required = false)
    public void setRunBudgetTracker(com.wechatbot.fashion.graph.budget.RunBudgetTracker budgetTracker) {
        this.budgetTracker = budgetTracker;
        try {
            this.compiledGraph = compile(trajectoryRecorder);
        } catch (Exception e) {
            log.warn("fashion-graph: 挂载执行预算失败: {}", e.getMessage());
        }
    }

    private CompiledGraph compile(AgentTrajectoryRecorder recorder) throws Exception {
        CompileConfig.Builder compileBuilder = CompileConfig.builder()
                .recursionLimit(25)
                .withLifecycleListener(new TrajectoryLifecycleListener(recorder, budgetTracker));
        // HITL：在 confirm 节点前中断，等待用户确认后 resume（仅命中付费意图的请求会走到该节点）
        if (ctx.hitlEnabled()) {
            compileBuilder.interruptBefore(FashionGraphDefinition.N_CONFIRM);
        }
        SaverConfig saverConfig = checkpointSaver(redissonClient);
        if (saverConfig != null) {
            compileBuilder.saverConfig(saverConfig);
        }
        return FashionGraphDefinition.build(ctx).compile(compileBuilder.build());
    }

    /**
     * 构建 Redis checkpoint saver；Redis 未装配或不可达时返回 {@code null}。
     *
     * <p>Redis 是 README 中的可选依赖：这里先做一次轻量连通性探测（EXISTS），失败则让图以
     * 「无 checkpoint」方式编译运行，保证穿搭管道不因 Redis 缺失而整条失效（代价是跨重启恢复/HITL 不可用）。
     */
    private static SaverConfig checkpointSaver(RedissonClient redissonClient) {
        if (redissonClient == null) {
            log.info("fashion-graph: RedissonClient 未装配，图将在无 checkpoint 模式下运行");
            return null;
        }
        try {
            redissonClient.getBucket("__fashion_graph_probe__").isExists();
        } catch (Exception e) {
            log.warn("fashion-graph: Redis 不可达（{}），图退化为无 checkpoint 运行（重启恢复/HITL 不可用）",
                    e.getMessage());
            return null;
        }
        AgentStateFactory<OverAllState> stateFactory = (Map<String, Object> m) -> new OverAllState(m);
        RedisSaver saver = RedisSaver.builder()
                .redisson(redissonClient)
                .stateSerializer(new SpringAIJacksonStateSerializer(stateFactory))
                .ttl(24, TimeUnit.HOURS)
                .build();
        return SaverConfig.builder().register(saver).build();
    }

    /** 执行一次图调用（异步返回最终 OverAllState）。 */
    public Optional<OverAllState> run(Map<String, Object> inputs, String threadId) {
        Map<String, Object> withRun = ensureRunId(inputs);
        return compiledGraph.invoke(withRun, RunnableConfig.builder().threadId(threadId).build());
    }

    /** 对外入口：执行图并返回最终 FashionResult（整图产出）。 */
    public Optional<FashionResult> runForResult(FashionRequest request, String threadId) {
        Map<String, Object> inputs = FashionState.initialInputs(
                request.userId(), request.userInput(), UUID.randomUUID().toString());
        Optional<OverAllState> state = compiledGraph.invoke(inputs, RunnableConfig.builder().threadId(threadId).build());
        return state.flatMap(s -> FashionState.readJson(s, FashionState.RESULT, FashionResult.class, ctx.objectMapper()));
    }

    /** 保证初始输入带 runId（用于轨迹关联）；已存在则不覆盖。 */
    private static Map<String, Object> ensureRunId(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of(FashionState.RUN_ID, UUID.randomUUID().toString());
        }
        if (inputs.containsKey(FashionState.RUN_ID)) {
            return inputs;
        }
        inputs.put(FashionState.RUN_ID, UUID.randomUUID().toString());
        return inputs;
    }

    /** 用 threadId 从 Redis checkpoint 恢复最近一次 state（模拟重启恢复）。 */
    public Optional<StateSnapshot> recover(String threadId) {
        return compiledGraph.stateOf(RunnableConfig.builder().threadId(threadId).build());
    }

    /** 该 threadId 是否存在「等待用户确认」的暂停 run（暂停在 confirm 节点前）。 */
    public boolean isPaused(String threadId) {
        return compiledGraph.stateOf(RunnableConfig.builder().threadId(threadId).build())
                .map(snapshot -> FashionGraphDefinition.N_CONFIRM.equals(snapshot.next()))
                .orElse(false);
    }

    /**
     * 用用户的确认结果恢复暂停的 run，并返回最终 {@link FashionResult}。
     *
     * <p>恢复携带 {@link FashionState#CONFIRM_APPROVED}：确认继续走 rag→stylist…，取消直接出取消兜底结果。
     * 依赖 checkpoint（Redis 或框架内存 saver）；无 checkpoint 时恢复会从头执行，属于已知限制。
     */
    public Optional<FashionResult> resumeForResult(String threadId, boolean approved) {
        try {
            RunnableConfig base = RunnableConfig.builder().threadId(threadId).build();
            // 把确认结果作为「confirm 节点的状态更新」写回 checkpoint，再继续执行。
            // 框架不会自动回灌暂停前状态给后续节点，故这里以暂停快照的完整 state 为基础更新。
            java.util.Map<String, Object> updates = compiledGraph.stateOf(base)
                    .map(snapshot -> new java.util.HashMap<>(snapshot.state().data()))
                    .orElseGet(java.util.HashMap::new);
            updates.put(FashionState.CONFIRM_APPROVED, approved);
            RunnableConfig updated = compiledGraph.updateState(base, updates, FashionGraphDefinition.N_CONFIRM);
            // 注意：恢复时必须把状态 map 作为 inputs 一并传入，否则后续节点拿不到暂停前的 state。
            Optional<OverAllState> state = compiledGraph.invoke(new java.util.HashMap<>(updates), updated);
            return state.flatMap(s -> FashionState.readJson(s, FashionState.RESULT, FashionResult.class, ctx.objectMapper()));
        } catch (Exception e) {
            log.warn("fashion-graph: 恢复暂停的 run 失败 threadId={}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }
}
