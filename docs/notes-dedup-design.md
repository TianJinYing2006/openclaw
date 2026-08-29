# 去重设计优化 — 问题发现与方案

> 项目：微信 AI 穿搭助手（WeChatBot）
> 日期：2026-08-22
> 背景：简历数据核验（`scripts/verify_resume_data.sh --live`）发现检索 strict top-5 命中仅 46.4%（top-20 候选覆盖 85.7%）；进一步追问"短时间内不重复推荐同一套穿搭"的设计对 top-k 准确率的影响。

---

## 1. 问题

**设计**：系统保证短时间内不向同一用户重复推荐同一套穿搭（滑动窗口去重 + 多样性采样）。
**疑问**：该设计是否在损害 top-k 检索准确率？损害多大？是否值得保留？

## 2. 机制确认（代码事实）

生产链路（`AgentCoordinator` Step 2）：

```
用户消息
  → QueryAnalyzer（LLM 分析）
  → conversationService.findRecentReferenceOutfits(userId, 5)   # 最近 5 条采纳记录内的 outfit 编号
  → knowledgeService.retrieveExcluding(query, excludeIds)        # 候选池内 removeIf 硬删除
  → RetrievalDiversitySampler（生产默认开，候选池 15→加权随机抽 5）
  → top-5 供 Stylist
```

- 去重窗口：`RECENT_RECOMMENDATION_WINDOW = 5`（最近 5 条带采纳记录的推荐）
- 删除方式：**硬删除**（`removeIf`）——若 RAGFlow 排第 1 的恰好是最近推荐过的，该轮 top-5 永远不可能含正确答案

> 重要澄清：46.4% 的评测口径**未开启去重**（`ResumeBenchmarkLiveTest` 显式 `diversity.enabled=false` + 空 excludeIds），衡量的是"检索能力上限"；**生产真实采纳质量会低于该值**。

## 3. 实证（MySQL 窗口函数，2026-08-22）

| 指标 | 数值 |
|---|---|
| 带采纳记录总数 | 57 条（3 个用户） |
| 答案在"最近 5 条已推荐"内出现过（会被硬删） | **15 条 = 26.3%** |
| 窗口放宽到 10 条后 | 16 条 = 28.1% |

**结论**：约 1/4 场景中,"正确答案"恰好落在去重窗口内被强制排除，用户实际采纳的是次优选项——这是"保证不重复"的可量化准确率代价（攻击面上限；实际损失 = 26.3% × 这些场景中 gt 本会进 top-5 的比例）。

## 4. 本质：多样性与准确率的权衡（exploration vs exploitation）

"不重复推荐"与"每次都给最相关"天然冲突。业界标准做法不是二选一，而是**控制多样性预算**：

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **Top-N 保底**（推荐） | 前 2~3 slot 完全不参与去重/采样，仅剩余 slot 多样化 | 伤害从 5/5 缩到 2/5；实现简单 | 去重效果略下降 |
| **软降权** | recent 项 score × 0.3 留在候选池（不 removeIf） | 正确答案保住"在池内"，top-20 覆盖不劣化 | "坚决不重复"承诺变弱 |
| **MMR** | 边际相关最大化，λ 参数权衡 | 理论最优、可调 | 实现/调参成本高 |

硬删除是最伤准确率的一档（删除的是"相关性上限"本身）。

## 5. 建议

1. **评测口径区分**：检索能力上限（无去重） vs 生产采纳质量（带去重+采样）——两个数字分开记录，简历/汇报勿混用。
2. **P0 诊断增量**：56 条评测逐条标注"该 gt 是否撞去重窗口"，让优化后的数字同时回答两个口径。
3. **后续优化选项**：将硬删除改为"Top-N 保底 + 剩余 slot 多样"（若产品可接受偶发重复）或软降权；用 26.3% 基线做前后对照。
4. **简历叙事**：该设计本身是亮点，量化表述为"滑动窗口去重（最近 5 条）+ 多样性采样，实证 26.3% 场景牺牲最相关答案，据此评估预算式多样性的替换"。

## 关联

- 检索链路：`AgentCoordinator.java`（RECENT_RECOMMENDATION_WINDOW=5）、`FashionConversationService.findRecentReferenceOutfits`、`RetrievalDiversitySampler`
- 评测：`src/test/java/.../benchmark/ResumeBenchmarkLiveTest.java`、`scripts/verify_resume_data.sh`
- 完整 RAG 优化方案：`docs/rag-precision-plan.md`