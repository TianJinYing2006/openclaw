# P0 落地前 Review 清单（spring-ai-alibaba-graph 单脊梁 · 阶段 1–2）

> 配套：`AGENT_RUNTIME_TARGET.md` §10（单脊梁 Cutover 方案）。本清单为**落地前 review**——你确认后我才会碰业务代码。  
> 范围：仅 P0 即 §10.4 的**阶段 1–2**（引依赖 + 官方样例验证 + 建 `StateGraph`/Redis checkpoint 骨架）。阶段 3–5（迁 fashion 子图、删 A/B、P5 守门）见 §10.4，本清单不展开，仅在 §4/§5 列出精确待操作目标。  
> 状态：待逐项 review 确认。

---

## 0. 前提与风险（先读）

- 框架 `spring-ai-alibaba-graph` 仍 ~1.1.0 RC，**API 可能微调**。因此本清单里的 GAV 与类名均为**待验证候选**；一切以阶段 1 跑通官方样例后的真实签名为准。
- 不改 RAG / rerank 逻辑（§1 Q2 原地保留）；P5 守门基线 = **36.84%（规则重排，21/57）**，非旧文档的 43.9%（跨期不可比）。
- 阶段 1–2 全部为「新增代码 + 加依赖」，**不修改** `AgentCoordinator` / `SpringAiChatCompletionsGateway` 现有逻辑，失败可零影响回滚。

---

## 1. 依赖坐标（阶段 1 第一步）

- **候选 GAV（需验证）**：`com.alibaba.cloud.ai:spring-ai-alibaba-graph:<version>`
- **验证动作（必做）**：先到官方仓库 `alibaba/spring-ai-alibaba` 的 graph 模块 README / Maven Central 确认：
  1. 最新稳定或 RC 版本号；
  2. 对应的 **Spring AI / Spring Boot** 版本要求（项目 Java 21，需对齐避免冲突）；
  3. 是否需额外引入 `spring-ai-alibaba-core` 或 BOM。
- **引入位置**：`pom.xml` 依赖段，建议用 `<dependencyManagement>` 锁定版本。
- **回滚**：仅加依赖、不动业务代码 → 失败即 `git revert` 依赖块。

---

## 2. 官方样例验证计划（阶段 1 第二步，守门 = 样例可编译运行）

目标：在动业务代码前，先把框架 API 跑通一遍，消除「假设接口稳定」的风险。

- 拉取官方 `simple-graph` / graph 示例（或本地建一个最小 `StateGraph` 单测工程）。
- **逐项确认以下 API 真实存在并跑通**（阶段 2 编码直接引用）：
  | 关注点                  | 需确认的 API                                                           | 用途                      |
  | -------------------- | ------------------------------------------------------------------ | ----------------------- |
  | 图构建                  | `StateGraph` 的 `addNode` / `addEdge` / `addConditionalEdges`       | 组装 6 节点 + 边             |
  | 状态                   | `OverAllState` / `KeyStrategy`                                     | 节点间共享 state 读写          |
  | 节点                   | `NodeAction` / `AsyncNodeAction` 接口签名                              | 6 个节点实现                 |
  | 编译                   | `CompiledGraph` 的 `invoke` / `stream`                              | 执行图                     |
  | **Redis checkpoint** | `RedisSaver` / `RedisStateRepository` 或 `CheckpointSaver` 自接 Redis | 阶段 2 关键未知项，**必须在样例里落实** |
- **产出**：把实际类名/方法签名回填到本文档 §3，或另记 `P0_SAMPLE_NOTES.md`，供后续编码引用。
- **守门指标**：样例能在本地 `mvn` 编译并跑通一次完整图调用（含 checkpoint 落 Redis）。

---

## 3. StateGraph / Redis checkpoint 草图（阶段 2）

> 以下为**设计草图**，类名待 §2 验证后回填。

- **新建包**：`com.wechatbot.fashion.graph`（与旧 `runtime/` 平行，不覆盖）。
- **`FashionState`**：继承/组合 `OverAllState`，定义 6 节点共享 state key，例如：
  - `query`（用户输入）、`memory`（episodic+semantic 画像）
  - `ragContext`（RAG 召回 + Trend 已并入）、`plan`（planner 输出）
  - `stylistOut`（搭配生成）、`criticVerdict`（通过/打回）、`responderOut`（最终回复）
- **`FashionGraphDefinition`**：用 `StateGraph` 组装——
  - 顺序边：`retrieve_memory → planner → rag → stylist → critic → responder`
  - `isSimpleRequest()`（formality≤3 且 subQueries≤3）→ **conditional edge** 跳 `critic`/`responder` 走轻路径
  - `critic` 打回 → **回边 loop** 回 `stylist`（带次数上限，接 Guardrails `maxIterations`）
- **`FashionGraphRunner`**：编译 `CompiledGraph`，接入 **Redis checkpoint saver**（§2 落实的具体类）。
- **6 个 `NodeAction` 占位实现**（阶段 2 先返回固定/透传值，阶段 3 填真实逻辑）：
  | 类                    | 节点                | 类型  | 阶段 3 真实逻辑                          |
  | -------------------- | ----------------- | --- | ---------------------------------- |
  | `RetrieveMemoryNode` | `retrieve_memory` | 确定  | 读 semantic/episodic memory 入 state |
  | `PlannerNode`        | `planner`         | LLM | LLM 路由 + 关键词规则回退（替 38 正则）          |
  | `RagNode`            | `rag`             | 确定  | 调现有 RAG（原地保留）                      |
  | `StylistNode`        | `stylist`         | LLM | 搭配生成（原地保留现有 Stylist 提示）            |
  | `CriticNode`         | `critic`          | LLM | 约束校验，打回走回边                         |
  | `ResponderNode`      | `responder`       | LLM | 格式化/发图                             |
- **入口接线**：`FashionAgentService`（`@Tool("fashion_consultant")`）改为构建并持有 `CompiledGraph`，但**阶段 2 仅接线、不切换流量**（旧 `AgentCoordinator` 仍为主路径，便于对照）。

---

## 4. 9 个手搓骨架删除清单（阶段 4 执行删除；此处先列精确目标）

精确路径（`src/main/java/com/wechatbot/fashion/agent/runtime/`）——阶段 2 先确认这些类**不被新图引用**（若有编译耦合需先解耦），阶段 4 经 P5 守门后 `git rm` 整目录：

1. `AgentState.java` → 由 `OverAllState` 接管
2. `Node.java` → 由 `NodeAction` 接管
3. `AgentGraph.java` → 由 `StateGraph`/`CompiledGraph` 接管
4. `Plan.java` → 由 Planner 输出的 state 字段接管
5. `Planner.java` → 由 `NodeAction` 接管
6. `MemoryStore.java` → 由 Redis-backed 记忆接管
7. `ToolAdapter.java` → 由 MCP `@Tool` 接管
8. `Guardrails.java` → 由框架上下文工程 + 自定义 max-steps/token/分级 接管
9. `Reflexion.java` → 由 Critic 回边接管

---

## 5. 退役目标路径（仅列，阶段 3/5 执行，阶段 2 不动）

- `src/main/java/com/wechatbot/fashion/ai/fashion/look/agent/AgentCoordinator.java`  
  → 阶段 3 标 `@Deprecated` 并行对照；阶段 4 P5 守门后删。
- `src/main/java/com/wechatbot/fashion/ai/service/SpringAiChatCompletionsGateway.java`  
  → 阶段 5 迁工具层（MCP 工具节点 + Planner 规则回退）后删。

---

## 6. P5 守门命令（阶段 4/5 验证用，现已固化）

```bash
RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow \
  mvn test -Dtest=RuleRerankGridSearchLiveTest
```

- 评测集：`logs/eval_rerank.tsv`（57 条真实查询，已 gitignore，不入库）
- 依赖：RAGFlow @ `127.0.0.1:9380` 在线
- **守门线**：规则重排 top-5 = **36.84%（21/57）**，不退化即合格
- 参考：检索 strict top-5 = 24.6%（14/57）；候选池 top-20 覆盖 70.2%
- 测试类位置：`src/test/java/com/wechatbot/fashion/benchmark/RuleRerankGridSearchLiveTest.java`

---

## 7. 本 P0 阶段验收基线

- 阶段 1：官方样例可编译运行，Redis checkpoint API 已落实并记录真实类名。
- 阶段 2：图可构建（`CompiledGraph` 编译通过）；状态可经 Redis checkpoint 持久化（跑一次带 checkpoint 的 invoke，重启后能恢复）；旧业务流量不受影响。
- Langfuse 全链路 trace 在图调用路径上可见（沿用已接 OTel 导出）。

---

## 8. 回滚方案

- 阶段 1–2 全部为新增代码 + 加依赖，不修改旧 `AgentCoordinator` / `SpringAiChatCompletionsGateway` 逻辑。
- 任一步失败：`git revert` 新增文件 + 去掉 `pom.xml` 依赖块即可，**零影响现有运行**。

---

## 9. Review 确认勾选（请逐项确认后我才会写代码）

- [x] 依赖 GAV 已按官方确认（版本 + Spring AI 兼容）
- [x] 官方样例跑通，Redis checkpoint API 已落实并记录真实类名
- [x] 图定义骨架包名/路径无冲突（新建 `com.wechatbot.fashion.graph`）
- [x] 9 骨架删除清单路径正确（阶段 4 才真删）
- [x] 退役 A/B 路径正确（阶段 3/5 才动）
- [x] P5 守门命令与基线（36.84%）已确认
- [x] 回滚方案可接受

---

## 10. 执行记录（Stage 1 已完成 · 2026-08-30）

- **依赖引入**：`pom.xml` 新增属性 `<spring-ai-alibaba-graph.version>1.1.2.3</spring-ai-alibaba-graph.version>` + `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core` 依赖。经 Aliyun Maven 镜像解析成功（BUILD SUCCESS）。
- **Smoke test**：`src/test/java/com/wechatbot/fashion/graph/GraphSmokeTest.java` —— 构建 `StateGraph`（无参）→ 2 个 `AsyncNodeAction` 节点 → `START→A→B→END` 边 → `compile()`（默认内存 saver）→ `invoke(Map, RunnableConfig(threadId))`。断言最终 state 含节点写入值。
- **结果**：`Tests run: 1, Failures: 0, Errors: 0`，BUILD SUCCESS，耗时 47.5s（含全量重编译 390 主源 + 148 测试源）。
- **结论**：Route ① 技术可行性已实证；Spring AI 1.1.8 与 graph-core 1.1.2.3 兼容，Option B（降 Spring AI 版本）暂不需要。
- **遗留到 Stage 2**：Redis checkpoint 真实落库需引入 `org.redisson:redisson` 并构建 `RedissonClient`（连 127.0.0.1:6379 无密码，见 MEMORY.md 启动配方），再用 `RedisSaver.builder().redissonClient(...)` 接入 `CompileConfig.saverConfig(...)`。

## 11. 下一步（Stage 2）

按 §3 草图进入阶段 2：① 引入 `redisson` 依赖；② 建 `com.wechatbot.fashion.graph` 包下的 `FashionState`/`FashionGraphDefinition`/`FashionGraphRunner` + 6 个 `NodeAction` 占位（阶段 2 先透传，不切流量）；③ 接 `RedisSaver` 做 checkpoint；④ 验证「图可构建 + 状态可经 Redis 持久化（重启可恢复）」。依旧不改旧 `AgentCoordinator`/`SpringAiChatCompletionsGateway` 逻辑，失败可零影响回滚。

## 12. 执行记录（Stage 2 已完成 · 2026-08-30）

- **依赖**：`pom.xml` 新增属性 `redisson.version=3.40.0` + `org.redisson:redisson` 依赖（graph-core 把 redisson 当 optional，必须显式引）。Aliyun 镜像解析成功，拉取带 netty 4.1.135.Final。
- **新增包 `com.wechatbot.fashion.graph`**：
  - `FashionState.java`：6 节点共享 state key 常量 + `initialInputs(query)` + `MAX_CRITIC_LOOP=3`。
  - `FashionGraphDefinition.java`：`StateGraph` 6 节点（retrieve_memory→planner→rag→stylist→critic→responder）；`START` 条件边（短 query≤8 → responder 轻路径，否则深链）；`critic` 条件边（reject 且未超上限 → 回边 loop 回 stylist，approve → responder）。
  - `FashionGraphRunner.java`：编译图并接 `RedisSaver` checkpoint（24h TTL），暴露 `run(inputs,threadId)` 与 `recover(threadId)`（`stateOf`）。
  - `nodes/`（6 个 `AsyncNodeAction` 占位：RetrieveMemory/Planner/Rag/Stylist/Critic/Responder）：阶段 2 全透传，不切流量。旧 `AgentCoordinator`/`SpringAiChatCompletionsGateway` 未动。
- **Redis checkpoint 接线（实测可落库）**：
  ```java
  RedisSaver saver = RedisSaver.builder()
      .redisson(redissonClient)
      .stateSerializer(new SpringAIJacksonStateSerializer((Map<String,Object> m) -> new OverAllState(m)))
      .ttl(24, TimeUnit.HOURS).build();
  SaverConfig saverConfig = SaverConfig.builder().register(saver).build();
  CompileConfig compileConfig = CompileConfig.builder().saverConfig(saverConfig).recursionLimit(25).build();
  CompiledGraph g = FashionGraphDefinition.build().compile(compileConfig);
  ```
  `AgentStateFactory<OverAllState>` 即 `Function<Map,OverAllState>`，用 `OverAllState::new`；`StateSerializer` 必须非 null 传入。
- **验证**：`FashionGraphRedisRecoveryTest`——长 query 跑深路径 → 用「新 RedissonClient + 新 CompiledGraph + 同 threadId」从 Redis 恢复 state 断言 responder 输出一致 + `iteration>=2`（critic 回边发生且重启可恢复）。
- **结果**：`mvn test -Dtest=FashionGraphRedisRecoveryTest,GraphSmokeTest` → `Tests run: 2, Failures: 0`，BUILD SUCCESS。旧图 smoke test 同绿，无回归。
- **编译坑（已规避）**：① `OverAllState.value(String)` 推断 `Optional<Object>`，读 String 须 `value(String, String.class)`；② `RedissonClient` 非 `AutoCloseable`，测试用 `finally{ rc.shutdown(); }` 而非 try-with-resources。
- **结论**：Stage 2 验收基线（§7）达成——图可构建、状态可经 Redis 持久化（重启可恢复）、旧流量不受影响。下一步见 §3/§4/§5（阶段 3 迁 fashion 子图真实逻辑 + P5 守门后删 A/清空 runtime；阶段 5 迁 System B 删 gateway）。
- **回滚**：删 `src/main/java/com/wechatbot/fashion/graph/**` + `FashionGraphRedisRecoveryTest.java`，去掉 `pom.xml` 的 `redisson` 依赖块即可，零影响现有运行。
