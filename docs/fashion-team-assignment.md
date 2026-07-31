# 穿搭 Agent 框架分工方案

- **日期**: 2026-07-31
- **截止**: 周六 demo
- **状态**: 框架骨架已搭建完成，编译通过

---

## 一、已完成的框架骨架

以下文件已创建并编译通过，所有人基于此骨架并行开发：

### 模型层 `fashion.model`
| 文件 | 职责 |
|------|------|
| `FashionRequest.java` | 用户请求 (userId, userInput) |
| `FashionResult.java` | 管道最终输出 |
| `AnalyzedQuery.java` | 查询分析结果 + QueryParams + 关键词兜底 |
| `SeedEntry.java` | 种子数据条目 (映射 JSON + FTS5) |
| `RetrievedChunk.java` | RAG 检索片段 |
| `StylistOutput.java` | Stylist 输出 (3套方案) |
| `CriticOutput.java` | Critic 输出 (评审意见) |
| `TrendOutput.java` | Trend 输出 (趋势分析) |
| `CoordinatorOutput.java` | Coordinator 输出 (最终裁决) |

### Agent 层 `fashion.agent`
| 文件 | 职责 |
|------|------|
| `AgentLlmCaller.java` | **基座** - ChatModel 封装, JSON解析, 超时, 重试 |
| `AgentPrompts.java` | 5个Agent的系统提示词 |
| `StylistAgent.java` | 创意形象顾问 - 生成3套方案 |
| `CriticAgent.java` | 评审师 - 多维度打分 |
| `TrendAgent.java` | 趋势分析师 - 潮流验证 |
| `CoordinatorAgent.java` | 首席搭配师 - 最终裁决 |
| `AgentCoordinator.java` | **编排器** - 调用顺序+并行+降级 |

### RAG 层 `fashion.rag`
| 文件 | 职责 |
|------|------|
| `QueryAnalyzer.java` | LLM需求分析 + 关键词兜底 |
| `FashionKnowledgeService.java` | FTS5全文检索 + 上下文格式化 |

### 知识层 `fashion.knowledge`
| 文件 | 职责 |
|------|------|
| `FashionSchemaInitializer.java` | FTS5建表 + 种子数据导入(100条) |

### 入口层 `fashion`
| 文件 | 职责 |
|------|------|
| `FashionAgentService.java` | @Tool入口, 注册到ToolRegistry |
| `FashionResponseFormatter.java` | 结构化JSON → 微信文案 |

---

## 二、业务流程

```
用户: "今天我要去海边，帮我推荐一套穿搭"
  │
  ├─ FashionAgentService.consult()           [F]
  │
  ├─ Step 1: QueryAnalyzer.analyze()         [A]
  │   → {scene:beach, season:summer, formality:1}
  │   → LLM失败时关键词兜底
  │
  ├─ Step 2: FashionKnowledgeService.retrieve()  [A]
  │   → FTS5检索种子数据, 返回top5博主穿搭
  │   → 格式化为Agent prompt可用文本
  │
  ├─ Step 3: StylistAgent.execute()           [B]
  │   → 注入用户需求 + RAG知识 + 参数
  │   → 生成3套有风格差异的穿搭方案 JSON
  │   → 失败→安全兜底方案
  │
  ├─ Step 4: CriticAgent ∥ TrendAgent        [B/C 并行]
  │   → Critic: 多维度打分, 必须指出不足
  │   → Trend: 趋势匹配分, 流行/过时元素
  │   → 任一失败不影响另一个
  │
  ├─ Step 5: CoordinatorAgent.execute()       [C]
  │   → 综合Stylist+Critic+Trend做裁决
  │   → 优先级: 体型>场合>风格>趋势
  │   → 失败→降级到Stylist首选
  │
  └─ FashionResponseFormatter.format()        [F]
      → 最终方案 + 推荐理由 + 实用建议 + 备选风格
```

---

## 三、分工方案

### 成员 A — RAG 检索 + 查询分析

**负责文件**:
- `rag/QueryAnalyzer.java` — 调优Prompt, 提升参数提取准确率
- `rag/FashionKnowledgeService.java` — 优化FTS5查询, 改进相关性排序
- `knowledge/FashionSchemaInitializer.java` — 数据导入优化

**任务**:
1. 调优 QueryAnalyzer 的 Prompt，确保场景/季节/正式度提取准确
2. 优化 FTS5 查询构建逻辑（当前用 OR 拼接，可改进为加权查询）
3. 验证种子数据导入完整性（100条全部写入FTS5）
4. 测试不同查询的检索质量（海边、婚礼、通勤等场景）

**验收标准**:
- QueryAnalyzer 对 "去海边"、"参加婚礼"、"上班穿什么" 正确提取 scene
- FTS5 返回的 chunk 与查询场景相关
- 种子数据100条全部可检索

---

### 成员 B — Stylist Agent + Critic Agent

**负责文件**:
- `agent/StylistAgent.java` — 调优Prompt, 确保输出3套差异化方案
- `agent/CriticAgent.java` — 调优Prompt, 确保评审维度完整
- `agent/AgentPrompts.java` 中的 STYLIST 和 CRITIC 常量

**任务**:
1. 调优 Stylist Prompt，确保3套方案有明显风格差异（不能都是休闲风）
2. 调优 Critic Prompt，确保每套方案都有至少1个不足指出
3. 验证 JSON 输出格式与 StylistOutput/CriticOutput record 严格匹配
4. 测试边界情况：用户输入模糊时（如"随便推荐"）仍能生成合理方案

**验收标准**:
- Stylist 输出3套方案，风格标签各不相同
- Critic 对每套方案都有 dimensionScores 和 weaknesses
- JSON 解析成功率 > 90%（AgentLlmCaller 有3层解析兜底）

---

### 成员 C — Trend Agent + Coordinator Agent

**负责文件**:
- `agent/TrendAgent.java` — 调优Prompt, 确保趋势分析合理
- `agent/CoordinatorAgent.java` — 调优Prompt, 确保裁决逻辑正确
- `agent/AgentPrompts.java` 中的 TREND 和 COORDINATOR 常量

**任务**:
1. 调优 Trend Prompt，确保趋势评分合理（不要每套都是3分）
2. 调优 Coordinator Prompt，确保优先级顺序正确（体型>场合>风格>趋势）
3. 验证 Coordinator 输出的 refinedOutfit 是融合后的最优方案
4. 测试 Coordinator 能正确引用 Stylist 方案ID

**验收标准**:
- Trend 输出 trendingElements 和 datedElements 非空
- Coordinator 的 selectedSuggestionId 对应 Stylist 的某个方案
- refinedOutfit 的字段完整（top/bottom/shoes/accessories）

---

### 成员 D — 编排器 + 基础设施（已完成骨架）

**负责文件**:
- `agent/AgentLlmCaller.java` — 已完成，可按需微调
- `agent/AgentCoordinator.java` — 已完成骨架，负责集成联调
- `model/*.java` — 已完成全部 record 类

**任务**:
1. 维护框架骨架，解决其他成员遇到的编译/集成问题
2. Day 4 集成测试：串联完整管道
3. 监控管道总耗时，调整超时参数
4. 确保 AiTraceLogger 埋点（可选，时间够再加）

**验收标准**:
- 完整管道可端到端跑通
- 管道总耗时 < 30s
- 各Agent超时降级正常工作

---

### 成员 E — 规则引擎 + 安全兜底（MVP可简化）

**负责文件**:
- 新建 `knowledge/FashionRuleEngine.java`
- 新建 `knowledge/SafetyFallbackService.java`

**任务**:
1. 实现简单的规则预检：根据 scene 注入硬约束（如婚礼避免纯白）
2. 实现规则后校验：Coordinator 输出后检查是否违反硬约束
3. 实现安全兜底表：5-8个常见场景的固定方案
4. MVP可简化：只做婚礼场景的 avoid_white 规则 + 3条兜底

**验收标准**:
- 婚礼场景不会推荐纯白上装
- 所有Agent失败时返回安全兜底方案

---

### 成员 F — 入口 + 格式化 + 联调

**负责文件**:
- `FashionAgentService.java` — 已完成骨架
- `FashionResponseFormatter.java` — 已完成骨架

**任务**:
1. 验证 @Tool 注册正确，通用LLM能识别穿搭类请求并调用
2. 优化输出文案格式，确保微信阅读体验好
3. 准备3-5条演示用例
4. 录制演示视频

**验收标准**:
- 微信发送"今天去海边穿什么"能触发穿搭管道
- 输出文案格式清晰，包含最终方案+理由+备选
- 演示用例覆盖不同场景（海边/婚礼/通勤）

---

## 四、协作时间线

| 天 | 内容 | 负责人 |
|----|------|--------|
| Day 1 | 框架骨架已完成, 所有人拉代码 review 接口 | D(已完成) |
| Day 1 | 所有人理解自己模块的输入输出, 开始开发 | 全员 |
| Day 2-3 | A调RAG / B调Stylist+Critic / C调Trend+Coordinator / E做规则 / F做格式化 | A/B/C/E/F |
| Day 4 | D集成AgentCoordinator, 串联完整管道 | D + A/B/C |
| Day 5 | F集成入口, 端到端跑通 | F + D |
| Day 6 | Prompt调优, 边界测试, 演示准备 | 全员 |
| Day 7 | Demo | 全员 |

---

## 五、关键技术约定

### Agent 调用方式
所有Agent通过 `AgentLlmCaller.callAgent()` 调用LLM，不注入任何工具：
```java
// 统一调用模板
OutputType result = llmCaller.callAgent(
    AgentPrompts.XXX,        // 系统提示词
    userMessage,             // 结构化上下文
    OutputType.class,        // 输出类型
    maxTokens,               // token上限
    timeout                  // 超时
);
```

### 数据流
```
用户输入 → AnalyzedQuery → (RAG检索) → RAGContext
    → StylistOutput → (CriticOutput ∥ TrendOutput)
    → CoordinatorOutput → FashionResult → 格式化文案
```

### 降级链
```
QueryAnalyzer失败 → 关键词兜底
RAG失败 → 空知识, Agent依赖自身知识
Stylist失败 → 安全兜底方案
Critic失败 → 空评审, Coordinator自行判断
Trend失败 → 中性趋势分
Coordinator失败 → Stylist首选方案
全部失败 → SQLite安全兜底表
```

### 代码规范
- 所有模型类用 record，不可变
- Agent之间不直接通信，只通过AgentCoordinator传递
- 每个Agent的execute()方法签名已定义，不要改签名
- Prompt调优只改AgentPrompts.java中的常量
