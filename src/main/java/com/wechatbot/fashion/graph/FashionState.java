package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fashion 子图的共享 state key 定义（对应 AGENT_RUNTIME_TARGET.md §10 / P0_REVIEW_LIST.md §3）。
 *
 * <p>阶段 3：真实 LLM / RAG / Memory 逻辑已迁入 6 节点；本类集中维护状态键与初始输入构造。
 * 节点间状态读写统一走 {@link com.alibaba.cloud.ai.graph.OverAllState}，本类不封装 OverAllState，避免与框架耦合。
 *
 * <p>状态键语义：
 * <ul>
 *   <li>{@link #USER_ID} / {@link #QUERY}：一次调用的用户与原始输入（入口注入）。</li>
 *   <li>{@link #MEMORY}：用户画像上下文（retrieve_memory 产出）。</li>
 *   <li>{@link #EXCLUDE_IDS}：RAG 去重排除的最近推荐 outfit 编号（retrieve_memory 产出）。</li>
 *   <li>{@link #PLAN}：AnalyzedQuery 查询结果（planner 产出），驱动 SIMPLE 路由。</li>
 *   <li>{@link #SIMPLE} / {@link #CONVERSATION_ID}：planner 产出，分别控制是否跳过 Critic/Coordinator、对话持久化主键。</li>
 *   <li>{@link #RAG_CONTEXT}：RAG 召回上下文（rag 产出）。</li>
 *   <li>{@link #STYLIST_OUT} / {@link #STYLIST_FAILED}：stylist 产出，失败则预填 {@link #RESULT} 安全兜底。</li>
 *   <li>{@link #CRITIC_OUT} / {@link #TREND_OUT} / {@link #CRITIC_VERDICT}：critic 节点内部 Critic∥Trend 并行产出。</li>
 *   <li>{@link #ITERATION}：critic 回边计数（配合 {@link #MAX_CRITIC_LOOP} / Guardrails）。</li>
 *   <li>{@link #RESULT}：responder 最终组装的 FashionResult，是整图的对外产出。</li>
 * </ul>
 */
public final class FashionState {

    /** 微信用户 ID（入口注入）。 */
    public static final String USER_ID = "userId";
    /** 用户输入 query（入口注入）。 */
    public static final String QUERY = "query";
    /** 单次图执行 ID（入口注入），用于 Agent 轨迹 run 关联与回放。 */
    public static final String RUN_ID = "runId";
    /** episodic + semantic 用户画像 / 禁忌记忆（retrieve_memory 产出）。 */
    public static final String MEMORY = "memory";
    /** RAG 去重：最近已推荐的 outfit 编号列表（retrieve_memory 产出，List<String>）。 */
    public static final String EXCLUDE_IDS = "excludeIds";
    /** planner 输出 AnalyzedQuery（路由 + 子查询计划）。 */
    public static final String PLAN = "plan";
    /** planner 输出的结构化执行计划 ExecutionPlan（JSON 字符串），供后续节点消费。 */
    public static final String EXECUTION_PLAN = "executionPlan";
    /** 是否简单请求（formality≤3 且子查询≤3），planner 产出。 */
    public static final String SIMPLE = "simple";
    /** 对话持久化主键（planner 调 saveInitial 产出），null 表示未存。 */
    public static final String CONVERSATION_ID = "conversationId";
    /** RAG 召回上下文。 */
    public static final String RAG_CONTEXT = "ragContext";
    /** stylist 生成的搭配（StylistOutput）。 */
    public static final String STYLIST_OUT = "stylistOut";
    /** stylist 是否失败（失败则本图直接以安全兜底 RESULT 结束）。 */
    public static final String STYLIST_FAILED = "stylistFailed";
    /** critic 评审产出（CriticOutput）。 */
    public static final String CRITIC_OUT = "criticOut";
    /** trend 趋势分析产出（TrendOutput）。 */
    public static final String TREND_OUT = "trendOut";
    /** critic 裁决：approve / reject（由 CriticOutput 评分推导）。 */
    public static final String CRITIC_VERDICT = "criticVerdict";
    /** responder 最终组装的 FashionResult（整图对外产出）。 */
    public static final String RESULT = "result";
    /** critic 回边循环计数（接 Guardrails maxIterations）。 */
    public static final String ITERATION = "iteration";
    /** tool_loop 节点产出的外部工具增强上下文（如实时天气），String；未触发为空串（P0-2 自主工具循环）。 */
    public static final String TOOL_CONTEXT = "toolContext";
    /** HITL：本次请求是否需要用户在付费/高费用操作前确认（planner 产出）。 */
    public static final String CONFIRM_REQUIRED = "confirmRequired";
    /** HITL：用户确认结果（恢复时经 addStateUpdate 注入），Boolean。 */
    public static final String CONFIRM_APPROVED = "confirmApproved";
    /** HITL：confirm 节点的裁决结果（approved / rejected）。 */
    public static final String CONFIRM_RESULT = "confirmResult";

    /** critic 回边最大次数（阶段 3 由 Guardrails / 配置接管）。 */
    public static final int MAX_CRITIC_LOOP = 3;

    private FashionState() {
    }

    /** 构造一次图调用的初始输入（含 userId / query / 循环计数初值）。 */
    public static Map<String, Object> initialInputs(String userId, String query) {
        return initialInputs(userId, query, null);
    }

    /** 构造一次图调用的初始输入，并注入本次运行的 runId（Agent 轨迹关联）。 */
    public static Map<String, Object> initialInputs(String userId, String query, String runId) {
        Map<String, Object> m = new HashMap<>();
        m.put(USER_ID, userId);
        m.put(QUERY, query);
        m.put(ITERATION, 0);
        if (runId != null && !runId.isBlank()) {
            m.put(RUN_ID, runId);
        }
        return m;
    }

    /**
     * 富类型状态值的编解码：spring-ai-alibaba-graph 的 checkpoint 序列化器不保留自定义 record 类型
     * （反序列化后嵌套 record 会退化成 LinkedHashMap），因此本图约定——
     * 仅 {@code String} / 原生类型 / {@code List<String>} 作为原生状态值，所有自定义 model（AnalyzedQuery /
     * StylistOutput / CriticOutput / TrendOutput / FashionResult 等）一律以 JSON 字符串形式存放，
     * 由节点边界用共享 ObjectMapper 自行（反）序列化，确保跨 Redis checkpoint 往返类型安全。
     */
    public static String writeJson(Object value, ObjectMapper om) {
        if (value == null) {
            return null;
        }
        try {
            return om.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JSON 字符串状态值反序列化为富类型；缺失/解析失败返回 empty。 */
    public static <T> Optional<T> readJson(OverAllState state, String key, Class<T> clazz, ObjectMapper om) {
        return state.value(key, String.class).flatMap(json -> {
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(om.readValue(json, clazz));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
