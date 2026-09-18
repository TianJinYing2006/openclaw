# Critic 消融实测结果（live · 2026-09-18）

> 数据由 `FashionAgentAblationLiveTest` 在固定查询集（10 条，`src/test/resources/golden/ablation_queries.txt`）
> 上真实运行产生。**这是结构/成本侧 + 有限确定性质量侧的实测**；约束满足率/幻觉率等需 LLM-as-judge。

## 1. 两次有效运行

| 运行 | 检索源 | 模型 | 说明 |
|---|---|---|---|
| A | MySQL FULLTEXT | `qwen3.7-plus` | 40/40 有效 |
| B | **RAGFlow（生产口径）** | `qwen3.7-flash` | 40/40 有效 |

（另有若干次因服务商 `HTTP 400 Access to model denied` 配额/权限受限而作废的运行，见 §4。）

## 2. 结果

每配置 10 条查询，每条给出 wall-clock。

**运行 A：MySQL FULLTEXT + `qwen3.7-plus`**

| 配置 | 方案数=3 | 含 outfit 编号 | 降级 | critic 均分 | median | p95 |
|---|---|---|---|---|---|---|
| C1 stylist-only | 10/10 | 10/10 | 0 | — | 11,390ms | 14,993ms |
| C2 stylist+critic | 10/10 | 10/10 | 0 | 3.2 | 28,049ms | 31,897ms |
| C3 stylist+trend | 10/10 | 10/10 | 0 | — | 17,831ms | 18,814ms |
| C4 full | 10/10 | 10/10 | 0 | 3.0 | 44,894ms | 51,538ms |

**运行 B：RAGFlow（生产口径） + `qwen3.7-flash`**

| 配置 | 方案数=3 | 含 outfit 编号 | 降级 | critic 均分 | median | p95 |
|---|---|---|---|---|---|---|
| C1 stylist-only | 10/10 | 10/10 | 0 | — | 11,936ms | 13,926ms |
| C2 stylist+critic | 10/10 | 10/10 | 0 | 3.7 | 24,673ms | 27,164ms |
| C3 stylist+trend | 10/10 | 10/10 | 0 | — | 19,170ms | 20,135ms |
| C4 full | 10/10 | 10/10 | 0 | 3.7 | 41,872ms | 46,822ms |

**两版一致：Critic 打回 0/10**（评分 2~4，阈值 2），无降级，四组均产出 3 套含 `referenceOutfitId` 的方案。

## 3. 结论（按预承诺决策规则）

1. **成本明确且可复现**：相对 C1，评审链路延迟——
   - 运行 A：`+critic` ≈ **2.5x**、`+trend` ≈ **1.6x**、`full` ≈ **3.9x**；
   - 运行 B：`+critic` ≈ **2.1x**、`+trend` ≈ **1.6x**、`full` ≈ **3.5x**。
2. **质量增益无证据**：两版共 20 条 query 中 Critic **从未打回**，评审未对 Stylist 输出产生任何修正；
   Coordinator 全部正常返回，无「降级救场」。
3. **决策**：**维持 `critic-loop` 默认关闭**，不默认启用 Coordinator；在 LLM-as-judge 质量证据出现前不宣称收益。
   与 rerank / query 改写「无正收益即关闭」一致。

## 4. 局限与已发现的问题

- **用户可见文本质量（LLM-as-judge）**：已用现有判官管线跑了一版小样本（n=12），
  结果见 **[agent-judge-results.md](agent-judge-results.md)**：完整管线 A 1:11 负于裸基线 B，
  疑似主因是 A 文本夹带 `outfit_XXX` 内部标记与画像措辞（且比较未过生产 Formatter，可能高估劣势）。
  这进一步支持「评审链路收益未被数据证明」，并给出可执行的输出清理改进项。
- **服务商配额坑**：连续多次 live 运行后，直连最小请求对 `qwen3.7-flash` 与 `qwen3.7-plus` 均返回
  `HTTP 400 Access to model denied`；等待后自行恢复。属账户侧限流/配额，不是代码或 RAGFlow 问题。
- **运行 B 的“只返回首个高相关”警告**：Coordinator 有 20 条提示倾向直接采用首选方案，这也解释了
  为何 Coordinator 未产生额外修正。

## 5. 复现

```bash
# 前置：MySQL/Redis 可达；模型密钥就绪
# 运行 A（MySQL FULLTEXT + plus）
FASHION_ABLATION_LIVE=true mvn test -Plive -Dtest=FashionAgentAblationLiveTest \
  -DargLine="-Dapp.ai.fashion-model=qwen3.7-plus -Dapp.fashion.rag.provider=mysql"

# 运行 B（RAGFlow 生产口径 + flash；需 9380 可达且本地 provider=ragflow）
FASHION_ABLATION_LIVE=true mvn test -Plive -Dtest=FashionAgentAblationLiveTest \
  -DargLine="-Dapp.ai.fashion-model=qwen3.7-flash"

# 明细报告：target/golden/critic-ablation-live.md
```

> `-DargLine` 作为 surefire JVM 系统属性传入，优先级高于配置文件。
