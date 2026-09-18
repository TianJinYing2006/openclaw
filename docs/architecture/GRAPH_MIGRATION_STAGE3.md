# 阶段 3：Fashion 子图真实逻辑迁移 + 特征开关方案

> 状态：待评审（用户确认后执行）
> 目标：把 `AgentCoordinator` 的 5 步管道迁进 6 个 graph 节点；默认仍走老路径，图以「影子/对照」方式并行运行，不切流量；经 P5 守门（36.84%）后翻转开关、删 `AgentCoordinator` + 清 `runtime/`。

## 1. 修正后的图拓扑（与原管道语义对齐）

原管道：Step0 画像 → Step1 分析 → Step2 RAG → Step3 Stylist →（简单?跳过 4/5）→ Step4 Critic∥Trend → Step5 Coordinator。
「简单」判定依赖 `AnalyzedQuery`（Step1 产出），故**分支点必须在 planner 之后、Stylist 之后**，而非 START。

```
START → retrieve_memory → planner → rag → stylist
                                      │
                          (STYLIST_FAILED || SIMPLE ? responder : critic)
                                      │
                                    critic
                                      │
                (loopEnabled && verdict==reject && iteration<MAX ? stylist : responder)
                                      │
                                  responder → END
```

> 注：Stage 2 的 `START → (simple? responder)` 条件边**删除**（会导致简单请求跳过 Stylist，与原语义不符）。

## 2. 节点 → 现有服务迁移映射

| 节点 | 调用 Bean / 方法 | 降级 | 写入状态键 | 读取状态键 |
|---|---|---|---|---|
| `retrieve_memory` | `userProfileService.buildProfileContext(userId, query)`；`conversationService.findRecentReferenceOutfits(userId, 5)` | 画像空串继续；excludeIds 空 | `MEMORY`(String), `EXCLUDE_IDS`(List<String>) | `USER_ID`, `QUERY` |
| `planner` | `queryAnalyzer.analyze(query, profileContext)`（异常→`AnalyzedQuery.fallback`）；`conversationService.saveInitial(...)` | fallback 分析；params 空则不存对话 | `PLAN`(AnalyzedQuery), `SIMPLE`(Boolean), `CONVERSATION_ID`(Long) | `QUERY`, `MEMORY`, `USER_ID` |
| `rag` | `knowledgeService.retrieveExcluding(query, Set.copyOf(excludeIds))`（异常→空）；`knowledgeService.formatContext(chunks)` | 空上下文 | `RAG_CONTEXT`(String) | `PLAN`, `EXCLUDE_IDS` |
| `stylist` | `stylistAgent.execute(request, ragContext, query, profileContext)`（异常→null） | null/empty → 置 `STYLIST_FAILED=true` 并预填 `RESULT=safetyFallback` | `STYLIST_OUT`(StylistOutput), `STYLIST_FAILED`(Boolean), `RESULT`(FashionResult,仅兜底) | `REQUEST`, `RAG_CONTEXT`, `PLAN`, `MEMORY` |
| `critic` | `criticAgent.execute(stylist, query)`（异常→empty）∥ `trendAgent.execute(stylist, query)`（异常→neutral，**若折叠**） | 见 Trend 决策 | `CRITIC_OUT`(CriticOutput), `TREND_OUT`(TrendOutput), `CRITIC_VERDICT`(String), `ITERATION`(+1) | `STYLIST_OUT`, `PLAN`, `ITERATION` |
| `responder` | `coordinatorAgent.execute(request, stylist, critic, trend, compactRagContext(ragContext))`（异常→null 降级） | coordinator=null → `coordinatorFromStylist` 降级；simple → `coordinatorFromStylist` | `RESULT`(FashionResult) | `STYLIST_OUT`,`CRITIC_OUT`,`TREND_OUT`,`RAG_CONTEXT`,`PLAN`,`REQUEST`,`SIMPLE`,`CONVERSATION_ID`,`RESULT`(兜底) |

- `REQUEST = new FashionRequest(userId, query)` 由 `retrieve_memory`/`planner` 构造并写入状态（FashionRequest 为 record，Jackson 可序列化）。
- `responder` 持久化（`updateRecommendation`/`updateReferenceOutfit`/`saveEmbeddingAsync`）**仅当图是权威路径时执行**（`shadow=false`）；影子模式不持久化，由老路径独占，避免双写。

## 3. 状态键清单（FashionState 调整）

新增/更名：`USER_ID`(String)、`EXCLUDE_IDS`(List<String>)、`CRITIC_OUT`(CriticOutput)、`TREND_OUT`(TrendOutput)、`COORDINATOR_OUT`(CoordinatorOutput，**可选，调试用**)、`RESULT`(FashionResult)、`CONVERSATION_ID`(Long)、`SIMPLE`(Boolean)、`STYLIST_FAILED`(Boolean)。
保留：`QUERY`、`MEMORY`、`PLAN`(AnalyzedQuery)、`RAG_CONTEXT`、`STYLIST_OUT`、`CRITIC_VERDICT`、`ITERATION`、`MAX_CRITIC_LOOP`。
移除：`RESPONDER_OUT`（由 `RESULT` 替代）。

## 4. Critic「驳回」判定（无显式 verdict 字段）

`CriticOutput` 仅含 `List<Critique>`，每条有 `overallScore`(1–5)。驳回规则：
`bestScore = max(reviews.overallScore)`，空评审记 0；`reject = bestScore < app.fashion.graph.critic-pass-threshold`（默认 2）。
- `CRITIC_VERDICT = reject ? "reject" : "approve"`。
- 循环默认**关闭**（`app.fashion.graph.critic-loop.enabled=false`）→ `criticRoute` 恒走 responder，与原管道「critic 只跑一次」完全一致，保证 P5 基线不漂移。
- 守门通过后可开启循环作为增益。

## 5. Trend 处理（待确认）

- 方案 B（推荐）：`critic` 节点内部以 `parallelExecutor` 并行跑 `criticAgent` + `trendAgent`，存 `CRITIC_OUT`+`TREND_OUT`，行为与原 Step4 一致，P5 风险最低。
- 方案 A：图内丢弃 Trend（`TREND_OUT=TrendOutput.neutral()`），Coordinator 入参仍传 neutral。实现最简，但丢失趋势增强，可能影响 P5，需重测。

## 6. 特征开关方案

`application-*.properties` 新增：
```
app.fashion.graph.enabled=false            # 总开关（默认关 = 老路径）
app.fashion.graph.shadow=true              # enabled 时：true=影子对照(老路径出用户结果,图仅对比); false=图权威
app.fashion.graph.critic-loop.enabled=false
app.fashion.graph.critic-pass-threshold=2
```

`FashionAgentService.consult` 切换逻辑：
```
FashionResult result;
if (graphRunner != null && graphEnabled) {
    FashionResult g = graphRunner.runForResult(request, threadId);
    if (graphShadow) { logCompare(g, coordinator.process(request)); result = coordinator.process(request); }
    else { result = g; }   // 守门后翻转
} else {
    result = coordinator.process(request);
}
// 以下 image-send + formatter.format(result) 完全不变
```
- `FashionGraphRunner` 注册为 `@Component`（构造需 `RedissonClient`）；`FashionAgentService` 以 `required=false` 注入。
- `AgentCoordinator` 标 `@Deprecated`，老路径保留至 P5 守门通过。

## 7. 执行风险

1. **Redis checkpoint 序列化 rich object**：状态含 `AnalyzedQuery/StylistOutput/CriticOutput/CoordinatorOutput/FashionResult`（均 `@JsonIgnoreProperties(ignoreUnknown=true)` 的 record，理论可序列化）。需在 `FashionGraphRedisRecoveryTest` 中改为真实对象断言，验证 Jackson 往返。若某类型不可序列化，改存可序列化的 String 摘要或对该键标记 `transient`（RC 支持 `OverAllState` 区分？否则降级为不 checkpoint 该类——但 checkpoint 是图级，需确认）。**最高优先级验证项。**
2. **行为漂移**：START 分支错误已修正；循环默认关；Trend 默认折叠 → 三道保险保证 P5 基线。
3. **并发**：`criticAgent`∥`trendAgent` 复用 `fashionAgentParallelExecutor`（注入 `FashionGraphRunner`/`critic` 节点）。
4. **持久化双写**：影子模式 graph 不持久化，老路径独占；权威模式 graph 持久化、老路径不调用。

## 8. 验证与守门

- 编译 + `FashionGraphRedisRecoveryTest`（真实对象往返）、`GraphSmokeTest` 仍绿。
- 开启 `enabled=true, shadow=true` 跑一批线上 query，对比老/图 `coordinator.refinedOutfit.referenceOutfitId` 与 `formatter.summarize` 一致性（日志）。
- 重跑 P5 benchmark（基线 36.84%）；不降 → 翻 `shadow=false`；观察稳定后删 `AgentCoordinator` + 清 `runtime/`。

## 9. 回滚

- 任意时刻 `app.fashion.graph.enabled=false` → 全量回老路径。
- 代码回滚：删 `graph/` 包 + 移除 `FashionAgentService` 中图注入与切换分支 + 移除 redisson 依赖块（同 Stage 2 回滚）。
