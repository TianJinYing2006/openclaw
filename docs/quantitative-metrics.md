# P5 量化数字（面试可复现口径 · 2026-08-31）

> 本文档为 `AGENT_RUNTIME_TARGET.md` §9 P1-4 交付：把"简单=2 次/深度=N 次 LLM 调用"从设计叙事变为
> **代码事实 + 实测数据**，面试被追问时可直接复现。所有数字来源见脚注，杜绝估算。

## 1. LLM 调用次数（代码拓扑，`src/main/java/.../graph/nodes/`）

图拓扑：`START → retrieve_memory → planner → rag → stylist → (简单? responder : critic) → responder`

| 节点 | LLM 调用 | 类型 | 说明 |
|---|---|---|---|
| `retrieve_memory` | **0 次** | 确定性 | 用户画像 + 最近推荐排除集 |
| `planner` | **1 次** | `QueryAnalyzer.analyze`（`callAgentGreedy`，temperature=0） | 场景/季节/正式度解析 + 简单判定 |
| `rag` | **0 次** | 确定性 | RAGFlow 检索 + 规则重排 |
| `stylist` | **1 次** | `StylistAgent.execute`（`callAgent`） | 搭配方案生成 |
| `critic`（仅复杂路径） | **2 次（并行）** | `criticAgent` ∥ `trendAgent`（`CompletableFuture` @parallelExecutor） | 约束校验 + 趋势，墙钟≈1 次 |
| `responder` | **0~1 次** | 简单路径=纯格式化；复杂路径=`CoordinatorAgent` 裁决 | 最终文案 |

**结论（代码事实）**：
- **简单请求**（formality≤3 且 subQueries≤3）：2 次 LLM 调用（planner + stylist）
- **复杂请求**：5 次 LLM 调用、墙钟≈4 次（critic∥trend 并行）
- 简单分支占比凭 `isSimpleRequest` 条件边在 `stylist` 后动态路由，常态流量多数为简单请求

## 2. 单次 LLM 时延（实测，2026-08-04 业务日志 `logs/today.log`，n=6）

| 分位 | 时延 |
|---|---|
| p50 | ~1,026ms |
| p95 | ~6,448ms |
| p99/max | ~9,037ms |
| 平均 | ~3,047ms |

> 口径：`AgentLlmCaller.callAgent` 单次调用（含网络/生成）；当前 30s 超时兜底、网络错误重试 1 次。

## 3. 端到端体验（推导口径，标注而非实测）

- 简单请求 = 2 次串行 LLM ≈ **1.5~3s**（以 p50 计）
- 复杂请求 = planner + stylist + critic∥trend + coordinator ≈ **4~8s**
- 参考图发送与文本解耦：图片先于文案到达（并行下载 + `ReferenceImageSendGate` 聚合）

## 4. Token 成本（可观测基建，Langfuse 导出后核验）

- `AgentLlmCaller` 已记录 `usage.prompt/completion/total tokens`（INFO 日志 + OTel span）
- `LANGFUSE_OTLP_ENABLED=true` + endpoint 开启后，Langfuse 按请求/trace 给出真实 token 与费用
- **当前默认关闭**（`application.properties`），未产生成本；真实验证需用户配 key

## 5. P5 守门（检索/重排质量，2026-08-31 实测）

| 指标 | 值 |
|---|---|
| 单路规则重排 top-5（生产口径） | **22/59 = 37.29%**（≥ 基线 36.84%） |
| 纯语义（无重排）top-5 | 14/59 = 23.73% |
| gt 候选池召回（top-50） | 55/59 = 93.22% |
| LLM reranker 实验 | 否决（-20pp，P2 实测） |
| 颜色约束 rerank 实验 | 否决（0 增益，根因=gt 历史采纳违反用户约束） |

## 6. 面试口径速记

> "穿搭图共 6 节点，只 2 处必须用 LLM（搭配生成 + 约束评审），其余检索/重排/画像全是确定性节点；
> 简单请求 2 次 LLM call（约 1.5~3s），复杂请求 5 次（critic/trend 并行，墙钟约 4 次）；
> 每步改动用 P5 离线回归锁不退化（37.29% ≥ 36.84%），两次 rerank 方向（LLM reranker、颜色约束）均受控实验后否决。"

---

*来源：`src/main/java/.../graph/nodes/*.java` 代码事实；`logs/today.log` 时延；`logs/p5_structured_output_rerun.log` P5 实测。*