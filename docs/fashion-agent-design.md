# 穿搭垂直 Agent 系统设计方案

- **版本**: v2.0
- **日期**: 2026-07-30
- **团队**: 6人
- **截止**: 2026-08-11

---

## 一、项目背景与目标

### 1.1 概述

基于已有 Spring Boot 3.5 + Spring AI 1.1 微信机器人，构建**穿搭垂直领域 AI Agent**。
用户通过微信聊天即可获得专业级穿搭推荐，系统具备从理解需求 → 知识增强 → 多角色推理 →
方案输出 → 持续学习的完整能力。

### 1.2 核心能力矩阵

| 能力 | 说明 | 技术支撑 |
|------|------|---------|
| 场景理解 | 理解用户模糊需求转化为结构化参数 | QueryAnalyzer + LLM |
| 专业知识 | 掌握色彩/体型/风格等穿搭规则 | RAGFlow + 规则引擎 |
| 创意生成 | 产出多套搭配方案而非单一回答 | Stylist Agent |
| 自我纠错 | 对方案进行评审和修正 | Critic Agent + Trend Agent |
| 趋势感知 | 结合当前流行趋势验证推荐 | Trend Agent + Web Search |
| 冲突裁决 | 创意与批评冲突时做出最优权衡 | Coordinator Agent |
| 组合搜索 | 在搭配空间中显式搜索最优解 | MCTS（可选）|
| 持续学习 | 从用户反馈中学习偏好 | Bradley-Terry |
| 视觉展示 | 生成试穿效果图 | VTON（已有）|
| 跨会话记忆 | 记住用户偏好和历史 | FashionMemoryService |

---

## 二、整体架构

### 2.1 系统分层图

`
┌─────────────────────────────────────────────────────────────┐
│                   交互层 (Interface)                         │
│     微信 iLink SDK / @Tool 注入 / FashionAgentService        │
└───────────────────────────┬─────────────────────────────────┘
                             │
┌───────────────────────────▼─────────────────────────────────┐
│                   编排层 (Orchestration)                     │
│                  AgentCoordinator (核心编排器)                │
│  · 控制 Agent 调用顺序与并行策略                              │
│  · 管理上下文传递和状态                                      │
│  · 每步超时控制 + 异常降级                                   │
│  · AiTraceLogger 埋点                                        │
└───────────────────────┬───┬───┬─────────────────────────────┘
                          │   │   │
              ┌───────────┘   │   └───────────┐
              ▼               ▼               ▼
┌────────────────┐ ┌───────────────┐ ┌──────────────┐
│ 推理层(Reason)  │ │ 知识层(Know)   │ │ 学习层(Learn) │
│ Stylist Agent  │ │ RAGFlow       │ │ Preference   │
│ Critic Agent   │ │  · 知识库管理   │ │ Learner(BT)  │
│ Trend Agent    │ │  · 混合检索    │ │ Session      │
│ Coordinator    │ │  · 内置 Rerank │ │ Archive      │
│ MCTS(可选)     │ │ 规则引擎       │ │              │
└────────────────┘ │ (预注入+后校验) │ └──────────────┘
                   └───────────────┘
                             │
┌───────────────────────────▼─────────────────────────────────┐
│                   持久层 (Persistence)                       │
│           SQLite (规则/记忆/偏好) + Caffeine Cache           │
└─────────────────────────────────────────────────────────────┘
`

### 2.2 架构原则

**Agent 隔离原则**: Agent 之间永远不直接通信。每个 Agent 只接收 Coordinator 下发的结构化上下文，输出严格 JSON。Stylist 不认识 Critic，Critic 不认识 Trend，所有中间结果汇聚到 Coordinator 统一裁决。

**一次检索，全部共享**: RAGFlow 知识检索在 Agent 启动前执行一次，结果封装为 RAGContext 注入所有 Agent 的 system prompt。不出现"Stylist 查一次、Critic 又查一次"的重复开销。

**确定性降级链**: 每一层都有精确的失败处理策略，保证用户永远不面对空响应或超时等待。

### 2.3 一次请求的完整流程

`
用户: "下周参加朋友婚礼穿什么"
 │
 ▼ Step 0: 路由判断
 穿搭类请求？→ FashionAgentService.queryClassifier
 是 → 走 Agent Pipeline | 否 → 走通用 Chat Gateway（已有）
 │
 ▼ Step 1: 上下文加载
 FashionMemoryService.loadSession(userId)
 → 加载用户档案 + 历史摘要 + 偏好权重向量
 │
 ▼ Step 2: 知识检索（一次查询，全局共享）
 2a. QueryAnalyzer 分解查询 + 提取参数
     → {queries:["婚礼宾客着装","半正式场合"], params:{scene:wedding}}
 2b. 规则预检：FashionRuleEngine.loadRules(params)
     → 硬约束: avoid_white, avoid_black; 软约束: formality>=3
 2c. RAGFlow 混合检索（对每个子查询）
     → {kb: fashion-rules, top_k: 5} + {kb: blogger-refs, top_k: 5}
 2d. ContextAssembler 组装 → 结构化 RAGContext
 │
 ▼ Step 3: 多 Agent 协作（严格隔离 + 并行）
 3a. Stylist Agent (LLM #1, timeout=15s) → 3套方案 JSON
 3b. Critic Agent (LLM #2, timeout=10s) ─┐ fork/join
     → 评审意见 JSON                      │
 3c. Trend Agent (LLM #3, timeout=15s)  ─┘ 并行执行
     → 趋势验证 JSON
 3d. Coordinator Agent (LLM #4, timeout=15s)
     → 综合裁决 + 精炼方案 JSON
 │
 ▼ Step 4: 规则后校验 + 领域评分
 规则引擎验证硬约束 → 通过/修正/降级
 领域综合评分 → 低于阈值则降级次优方案
 │
 ▼ Step 5: VTON 图生图（可选）
 │
 ▼ Step 6: 偏好记录 + 记忆归档
 │
 ▼ Step 7: 输出格式化 + 微信回复
`

---

## 三、模块详细设计

### 3.1 FashionAgentService (主入口)

**位置**: com.example.ykdsummer.ai.fashion.service.FashionAgentService
**类型**: @Component + @Tool 方法

**职责**:
1. 微信消息入口，判断是否走穿搭管道
2. 维护用户会话生命周期
3. 调用 AgentCoordinator 启动协作
4. 处理结果并格式化输出

`java
@Component
public class FashionAgentService implements AiTool {

    @Tool(name = "fashion_consultant",
          description = "穿搭推荐：根据场景、风格、体型推荐穿搭方案")
    public String consult(String userInput) {
        String userId = AgentSessionContext.currentUserId();
        UserProfile profile = memory.loadUserProfile(userId);
        FashionResult result = coordinator.process(
            new FashionRequest(userId, userInput, profile));
        memory.archiveSession(userId, result.sessionArchive());
        return formatter.format(result);
    }
}
`

**路由方式**:
- 方式A: 注册为 @Tool，由通用 LLM 判断调用
- 方式B: 在现有 Gateway 前加 filter，检测穿搭关键词路由到此

---

### 3.2 知识检索层

#### 3.2.1 QueryAnalyzer

用小模型（或低 token 模式）做查询分解。

**输入**: 用户原始问题 + 用户档案
**输出**: AnalyzedQuery

`java
public class AnalyzedQuery {
    String originalQuery;
    List<String> decomposedQueries;
    QueryParams params;  // {scene, season, formality, gender, style_hint}
}
`

**Prompt 概要**:
`
分析用户的穿搭需求，输出 JSON：
{
  "decomposedQueries": ["子查询1", "子查询2"],
  "params": {
    "scene": "wedding/date/work/sport",
    "season": "当前季节",
    "formality": "正式度1-5",
    "style_hint": "风格提示"
  }
}
`

**关键点**:
- 单次 LLM 调用，输出 ~100 tokens
- 可用本地 7B 模型或低 token 模式减少开销
- 需提供 fallback（关键词匹配兜底）

#### 3.2.2 RAGFlow 知识库

采用 RAGFlow 替代手动实现的 BM25 + 向量检索 + Reranker。RAGFlow 内置文档解析、自动分块、Embedding 和混合检索（BM25 + 向量 + Rerank），以 HTTP API 暴露。

**知识库设计**:

| 知识库 | 内容 | 用途 | 数据来源 |
|--------|------|------|---------|
| fashion-rules | 穿搭规则、色彩搭配、体型适配、场景规范 | 各 Agent 的规则参考 | docs + 人工录入 |
| blogger-references | 小红书等平台的博主穿搭案例 | Stylist 灵感来源 | 爬虫 + 种子 JSON |

**关键 API 调用（RAGFlow 标准接口）**:

`
POST /api/v1/retrieval
{
  "question": "婚礼宾客穿搭",
  "kb_ids": ["kb-fashion-rules-uuid", "kb-blogger-refs-uuid"],
  "top_k": 5,
  "similarity_threshold": 0.6
}

Response:
{
  "chunks": [{
    "content": "婚礼宾客应避免纯白和全黑...",
    "similarity": 0.92,
    "doc_name": "fashion-rules",
    "positions": [...]
  }]
}
`

**部署与集成要点**:
- RAGFlow 部署在 docker/ragflow/docker-compose.yml，端口 9380
- 通过 RagflowSearchService 封装 API 调用，注入到 RagflowRetriever
- 检索在 Agent 启动前统一执行一次，结果通过 RAGContext 共享给所有 Agent
- RAGFlow 不可用时跳过知识层，仅依赖规则引擎 + LLM 自身知识

#### 3.2.3 RagflowRetriever

`java
@Service
public class RagflowRetriever {

    private final RagflowSearchService ragflowClient;

    public RAGContext retrieve(AnalyzedQuery query) {
        // RAGFlow 一次混合检索（内置 BM25 + Vector + Rerank）
        List<RetrievedChunk> chunks = ragflowClient.search(
            query.decomposedQueries(), List.of("fashion-rules", "blogger-references"), 5);
        // 组装为 RAGContext
        return new RAGContext(query, chunks);
    }
}
`

#### 3.2.4 ContextAssembler

将 RAGFlow 返回的 RetrievedChunk 列表组装为 Agent prompt 可用的结构化文本。

`java
@Component
public class ContextAssembler {
    public String assemble(RAGContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 穿搭知识参考\n");
        for (RetrievedChunk chunk : ctx.chunks()) {
            sb.append("- [").append(chunk.docName()).append("] ")
              .append(chunk.content()).append("\n");
        }
        sb.append("\n## 用户需求参数\n");
        sb.append(ctx.query().toParamString());
        return sb.toString();
    }
}
`

#### 3.2.5 FashionRuleEngine

规则引擎承担双重职责：

- **前置注入**: 在 Stylist 思考前，把匹配的约束规则注入 prompt，让 Agent 天然避开错误
- **后置校验**: Coordinator 输出后进行硬约束筛查，违规则自动修正或降级

**规则存储**: SQLite fashion_rules 表

`sql
CREATE TABLE IF NOT EXISTS fashion_rules (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_type TEXT NOT NULL CHECK(rule_type IN ('hard','soft')),
  trigger_key TEXT NOT NULL,
  trigger_value TEXT NOT NULL,
  constraint_json TEXT NOT NULL,
  weight REAL DEFAULT 1.0,
  explanation TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rules_trigger ON fashion_rules(trigger_key, trigger_value);
`

**种子规则（推荐 30+ 条）**:

| trigger_key | trigger_value | 约束示例 | 类型 |
|------------|--------------|---------|------|
| scene | wedding | top_color != '纯白' | hard |
| scene | wedding | bottom_color != '全黑' | hard |
| scene | date | style in {浪漫, 优雅, 休闲} | soft |
| scene | work | formality >= 3 | hard |
| body_type | pear | bottom_silhouette in {A字裙, 阔腿裤} | soft |
| body_type | apple | top_silhouette in {V领, 宽松} | soft |
| season | summer | fabric in {棉, 亚麻, 真丝} | soft |
| season | winter | layers >= 2 | soft |
| color_scheme | contrast | 主色在色环距离 > 120度 | soft |

**安全兜底方案**（当所有 Agent 全部失败时使用）:

`sql
INSERT INTO safety_fallbacks (scene, recommendation_json) VALUES
('wedding', '{"top":"米白色衬衫","bottom":"浅色收腰连衣裙","shoes":"裸色高跟鞋","reasoning":"通用婚礼宾客安全方案"}'),
('date', '{"top":"简约针织衫","bottom":"A字半身裙","shoes":"小白鞋","reasoning":"通用约会安全方案"}'),
('work', '{"top":"白色衬衫","bottom":"黑色直筒西裤","shoes":"低跟皮鞋","reasoning":"通用通勤安全方案"}');
`

#### 3.2.6 种子数据集

**格式**（data/fashion_seed_data.json，200+ 条）:

`json
[
  {
    "id": "ref_001",
    "type": "blogger_look",
    "source": "小红书/@xx",
    "outfit": {
      "top": "浅粉色雪纺连衣裙",
      "shoes": "白色尖头细跟高跟鞋",
      "accessories": "珍珠耳环"
    },
    "tags": {
      "style": ["优雅", "浪漫"],
      "scene": ["wedding"],
      "season": ["summer"],
      "body_type": ["hourglass", "pear"],
      "color_scheme": "粉+白"
    },
    "summary": "婚礼宾客经典搭配"
  }
]
`

种子数据同时导入 RAGFlow blogger-references 知识库和 SQLite 参考表，RAGFlow 用于语义检索，SQLite 用于精确匹配。

---

### 3.3 多 Agent 协作层

#### 3.3.1 Agent 通信协议

`
1. Agent 之间不直接调用，不共享对话历史
2. 每个 Agent 的输入 = System Prompt + RAGContext + 上游输出
3. 每个 Agent 的输出 = 严格 JSON（由 Java 模型类定义 Schema）
4. 所有中间结果由 Coordinator 持有，不被 Agent 修改
5. JSON 解析失败时重试一次，再失败则用默认值替代该 Agent 输出
`

#### 3.3.2 AgentCoordinator (编排器)

`java
@Component
public class AgentCoordinator {

    private static final Duration STYLIST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CRITIC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TREND_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COORDINATOR_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PIPELINE_TOTAL_TIMEOUT = Duration.ofSeconds(30);

    public FashionResult process(FashionRequest request) {

        // Step 1: 知识预检
        AnalyzedQuery analyzed = queryAnalyzer.analyze(request);
        RAGContext ragCtx = ragflowRetriever.retrieve(analyzed);
        List<FashionRule> rules = ruleEngine.loadRules(analyzed.params());

        // Step 2: Stylist （串行，必须先生成方案）
        StylistOutput stylist = callWithTimeout(
            () -> callStylist(request, ragCtx, rules), STYLIST_TIMEOUT);
        if (stylist == null) {
            return fallbackSafetyPlan(request, "风格生成失败");
        }

        // Step 3: Critic + Trend 真并行
        CompletableFuture<CriticOutput> cfCritic =
            supplyAsync(() -> callWithTimeout(
                () -> callCritic(stylist, ragCtx), CRITIC_TIMEOUT));
        CompletableFuture<TrendOutput> cfTrend =
            supplyAsync(() -> callWithTimeout(
                () -> callTrend(stylist, ragCtx), TREND_TIMEOUT));

        CriticOutput critic = cfCritic.exceptionally(e -> {
            log.warn("Critic Agent failed", e);
            return CriticOutput.empty();
        }).join();

        TrendOutput trend = cfTrend.exceptionally(e -> {
            log.warn("Trend Agent failed", e);
            return TrendOutput.neutral();
        }).join();

        // Step 4: Coordinator 裁决
        CoordinatorOutput coord = callWithTimeout(
            () -> callCoordinator(request, stylist, critic, trend, ragCtx, rules),
            COORDINATOR_TIMEOUT);

        if (coord == null) {
            return fallbackToStylist(request, stylist, "Coordinator 超时");
        }

        // Step 5: 规则后校验 + 自动修正
        coord = ruleEngine.validateAndFix(coord, rules);

        return buildResult(stylist, critic, trend, coord);
    }
}
`

**异常处理**:

| 失败点 | 处理策略 |
|--------|---------|
| QueryAnalyzer 失败 | 关键词匹配兜底提取 params |
| RAGFlow 不可用 | 跳过知识层，仅用规则 + LLM 自身知识 |
| 规则引擎 SQLite 不可用 | 空规则列表，不注入约束 |
| Stylist 超时/失败 | 返回安全兜底方案 |
| Critic 超时/失败 | 返回 CriticOutput.empty()，Coordinator 自行判断 |
| Trend 超时/失败 | 返回 TrendOutput.neutral()（trend_score=3.0） |
| Critic + Trend 均失败 | Coordinator 仅基于 Stylist + 规则做裁决 |
| Coordinator 违反硬约束 | 自动修正（纯白->米白，全黑->深灰等）而非整方案降级 |
| Coordinator 超时/失败 | 降级到 Stylist 首选方案 + 规则校验 |
| 全部 Agent 失败 | 返回 SQLite 安全兜底表（3 条固定方案） |

#### 3.3.3 统一 Agent 调用模板

`java
private <T> T callAgent(String systemPrompt, Object context,
                         Class<T> type, int maxTokens) {
    Prompt prompt = new Prompt(
        List.of(new SystemMessage(systemPrompt),
                new UserMessage(toJson(context))),
        OpenAiChatOptions.builder()
            .model(aiProperties.getModel())
            .maxCompletionTokens(maxTokens)
            .build()
    );
    String resp = chatClient.prompt(prompt).call().content();
    return parseJson(resp, type);
}
`

每个 Agent 的 LLM 调用带独立 timeout、JSON 解析重试、AiTraceLogger 埋点。

#### 3.3.4 Stylist Agent

**角色**: 创意型形象顾问

**Prompt 要点**:
`
你是10年经验的职业形象顾问。
生成3套完整穿搭方案，每套包含上装/下装/鞋/配饰。
给出色彩方案名称和选择理由。
三套之间要有明显风格差异。
输出严格 JSON。
`

**输出结构**:
`json
{
  "suggestions": [{
    "id": 1,
    "style_label": "优雅浪漫风",
    "outfit": {
      "top": "米白色真丝衬衫",
      "bottom": "浅粉色A字裙",
      "shoes": "裸色尖头高跟鞋",
      "accessories": "珍珠耳钉、白色手拿包"
    },
    "color_scheme": "米白+浅粉（邻近色搭配）",
    "reasoning": "搭配理由",
    "suitable_for": ["婚礼宾客", "约会晚餐"],
    "body_type_notes": "适配说明"
  }]
}
`

#### 3.3.5 Critic Agent

**角色**: 严格评审师（与 Trend Agent 并行执行）

**Prompt 要点**:
`
对每套方案给出 1-5 分。
必须指出至少一个潜在问题。
评审维度：色彩和谐、体型适配、场合适配、整体协调。
不得只说好话。
输出严格 JSON。
`

**输出结构**:
`json
{
  "reviews": [{
    "suggestion_id": 1,
    "overall_score": 4,
    "dimension_scores": {"color_harmony":5,"body_fit":3,"scene_fit":4},
    "strengths": ["色彩柔和优雅"],
    "weaknesses": ["A字裙对H型身材不友好"],
    "improvements": ["建议改为收腰连衣裙"],
    "risk_flags": ["夏季真丝易出汗"]
  }]
}
`

#### 3.3.6 Trend Agent

**角色**: 趋势分析师（与 Critic Agent 并行执行）

**Prompt 要点**:
`
验证方案是否符合当前潮流。
给出趋势匹配分 1-5。
可调用 web_search 确认最新趋势。
考虑季节和地区因素。
输出严格 JSON。
`

**输出结构**:
`json
{
  "trend_analysis": [{
    "suggestion_id": 1,
    "trend_score": 4,
    "seasonal_match": "非常匹配",
    "trending_elements": ["真丝热门", "裸色流行"],
    "dated_elements": [],
    "search_summary": "2026夏季浪漫风格走强"
  }]
}
`

#### 3.3.7 Coordinator Agent

**角色**: 首席搭配师（最终裁决）

**Prompt 要点**:
`
综合 Stylist/Critic/Trend 的全部信息做最终裁决。
优先级：体型 > 场合 > 风格偏好 > 趋势。
可融合多套方案优点。
输出精炼方案配置，附最终推荐理由。
输出严格 JSON。
`

**输出结构**:
`json
{
  "final_recommendation": {
    "selected_suggestion_id": 1,
    "selection_reasoning": "色彩搭配最佳，Critic建议已整合",
    "elimination_notes": {"rejected_2": "过于休闲", "rejected_3": "温度适应性差"}
  },
  "refined_outfit": {
    "top": "米白色真丝衬衫（扎进裙腰）",
    "bottom": "浅粉色收腰连衣裙",
    "shoes": "裸色方头低跟凉鞋",
    "accessories": "珍珠耳钉、米白编织包"
  },
  "final_reasoning": "融合浪漫优雅风格...",
  "practical_tips": ["防皱喷雾", "凉鞋更适合户外"]
}
`

#### 3.3.8 MCTS 搜索引擎（可选）

**概述**: 将穿搭组合建模为搜索树，用 MCTS + LLM 评估探索最优路径。

**搜索树结构**:
- 深度 0: 根（用户需求）
- 深度 1: 风格选择（浪漫/通勤/街头）
- 深度 2: 色系选择
- 深度 3: 上装选择
- 深度 4: 下装选择
- 深度 5: 鞋/配饰（terminal）

**参数建议**:
- 迭代次数: 20-50
- 探索常数 C: 1.4
- Simulation 调用 Critic Agent 快速评分（低 max_tokens）
- 超时兜底: 10s

**注意**: MCTS 是锦上添花，时间不够可砍掉。优先级：完整管道 > RAG > 偏好学习 > MCTS。

---

### 3.4 偏好学习层 (Bradley-Terry)

#### 3.4.1 数学模型

方案特征向量: f = [f_color, f_body, f_scene, f_trend, f_style]

方案得分: s = w . f

用户选 A 而非 B 时的权重更新:
`
P(A > B) = sigma(s_A - s_B)
Delta_w = eta . (f_A - f_B) . (1 - P(A > B))
w_new = w_old + Delta_w
`

#### 3.4.2 反馈获取

- **显式反馈**: 用户说"第二套不错"或"帮我改了..."
- **隐式反馈**: 用户后续提到"上次推荐的那套我穿了" -> 视为采纳

偏好记录存入 preference_records 表。

#### 3.4.3 注入到 Agent

权重向量转为自然语言描述，注入 Stylist 和 Coordinator 的 prompt:

"该用户特别注重色彩搭配，对品牌不太敏感"

---

### 3.5 记忆服务 (FashionMemoryService)

三层记忆结构：

| 记忆层 | 内容 | 存储 |
|-------|------|------|
| 语义记忆 | 用户事实（性别/体型/年龄/城市） | user_profiles 表 |
| 偏好记忆 | 偏好风格/色系/品牌 + Bradley-Terry 权重 | user_profiles 表 |
| 会话摘要 | 每次对话的摘要文本 | session_archives 表 |

核心方法:

`java
@Component
public class FashionMemoryService {
    public UserProfile loadUserProfile(String userId);
    public void updateUserProfile(UserProfile profile);
    public void archiveSession(String userId, SessionArchive archive);
    public List<String> loadRecentSummaries(String userId, int limit);
    public boolean isNewUser(String userId);
}
`

会话摘要示例: "用户为婚礼咨询，推荐了优雅浪漫风格并带珍珠配饰，用户调整了鞋款"

---

### 3.6 非功能性设计

#### 3.6.1 Token 预算

| Agent | 输入 (估计) | 输出上限 | 单次调用成本 |
|-------|------------|---------|------------|
| QueryAnalyzer | 500 tokens | 100 | ~600 tokens |
| RAGFlow 检索 | 不消耗 LLM token | - | - |
| Stylist | 2000 tokens | 800 | ~2800 tokens |
| Critic | 1500 tokens | 400 | ~1900 tokens |
| Trend | 1500 tokens | 400 | ~1900 tokens |
| Coordinator | 2500 tokens | 600 | ~3100 tokens |
| 管道总计 | ~8000 tokens | ~2300 | ~10300 tokens/次 |

- 与普通对话共用 AiUsageMeter 预算，但 Agent 管道设独立 taskClass 做费率区分
- 单用户每日 Agent 调用限额设为普通对话的 1/3（因单次消耗高）

#### 3.6.2 超时控制

| 层级 | 超时 | 触发行为 |
|------|------|---------|
| 管道总超时 | 30s | 返回当前已产出的最佳部分结果 |
| QueryAnalyzer | 5s | 关键词匹配兜底 |
| RAGFlow 检索 | 5s | 跳过知识层 |
| 规则引擎查询 | 2s | 空规则列表 |
| Stylist | 15s | 安全兜底方案 |
| Critic | 10s | 空评审 |
| Trend | 15s | 中性趋势分 |
| Coordinator | 15s | 降级到 Stylist 首选 |
| VTON | 10s | 纯文字回复 |

#### 3.6.3 可观测性

每个 Agent 的输入/输出通过 AiTraceLogger 记录：

`java
trace.agentCall("Stylist", promptTokens, System.currentTimeMillis() - startMs);
trace.agentResult("Stylist", outputJson, success ? "success" : "fallback");
`

**关键指标**:
- 管道总耗时（P50/P95/P99）
- 各 Agent 单次调用耗时
- JSON 解析失败率
- 降级触发次数及原因分布
- RAGFlow 检索耗时和结果数

#### 3.6.4 安全兜底

当所有 Agent 全部失效（网络故障、LLM 服务宕机等极端情况），从 safety_fallbacks 表中取一条匹配用户场景的固定推荐方案。该表预置 5-8 个常见场景的通用安全方案，纯 SQL 查询，零 LLM 调用，保证用户在极端情况下也能收到回复。

---

## 四、包结构

`
com.example.ykdsummer.ai.fashion
├── FashionAgentService.java              -- @Tool 入口
├── FashionResponseFormatter.java         -- 输出格式化
├── model/
│   ├── FashionRequest.java
│   ├── FashionResult.java
│   ├── StylistOutput.java
│   ├── OutfitSuggestion.java
│   ├── CriticOutput.java
│   ├── Critique.java
│   ├── TrendOutput.java
│   ├── TrendReview.java
│   ├── CoordinatorOutput.java
│   ├── RefinedOutfit.java
│   ├── RAGContext.java
│   ├── AnalyzedQuery.java / QueryParams.java
│   ├── FashionRule.java
│   ├── UserProfile.java
│   ├── SessionArchive.java
│   ├── ValidationResult.java
│   └── PreferenceRecord.java
├── agent/
│   ├── AgentCoordinator.java
│   ├── AgentPrompts.java
│   ├── StylistAgent.java
│   ├── CriticAgent.java
│   ├── TrendAgent.java
│   └── CoordinatorAgent.java
├── rag/
│   ├── QueryAnalyzer.java
│   ├── RagflowRetriever.java             -- RAGFlow API 调用封装
│   └── ContextAssembler.java
├── knowledge/
│   └── FashionRuleEngine.java
├── learning/
│   ├── PreferenceLearner.java
│   └── FeatureExtractor.java
├── memory/
│   └── FashionMemoryService.java
└── integration/
    └── VTONIntegration.java
`

---

## 五、分工与时间线

| 成员 | 模块 | 代码量 |
|------|------|--------|
| A | RAGFlow 部署 + RagflowRetriever + QueryAnalyzer + 种子数据 | ~400行 |
| B | Stylist + Critic Agent | ~400行 |
| C | Trend Agent + Coordinator Agent | ~400行 |
| D | AgentCoordinator + Memory 服务 + 非功能性设计 | ~700行 |
| E | 规则引擎 + 偏好学习器 + 安全兜底 | ~500行 |
| F | 数据工程 + VTON + 格式化 + 端到端联调 | ~400行 |

### Day 1-4 (基建 + 独立开发)

- **Day 1**: D 创建数据模型 + 所有人对齐接口 + D/E 建表 + A/F 标注数据 + A 部署 RAGFlow Docker
- **Day 2-3**: A RagflowRetriever + QueryAnalyzer + B AgentPrompts 初版 + D AgentCoordinator 骨架 + E 规则引擎 + 安全兜底表
    * 验收: RAGFlow API 可调用并返回结果，QueryAnalyzer 输出正确 JSON
- **Day 4-5**: B Stylist + Critic 完整实现 / C Trend + Coordinator 完整实现 / D AgentCoordinator + 超时控制 + AiTraceLogger
    * 验收: 各自模块可独立测试，输出符合 JSON schema

### Day 5-7 (管道打通)

- B+C: 单 Agent 调用验证
- D: AgentCoordinator 串联测试（含并行的 Critic+Trend）
- A+D: RAGFlow 集成到 Agent 管道
- E: 规则引擎前后校验集成
- F: @Tool 入口 + 格式化

**Day 7 里程碑**: AgentCoordinator 完整管道跑通（含 RAGFlow 预查 -> Stylist -> Critic+Trend 并行 -> Coordinator -> 规则校验 -> 安全兜底）

### Day 8-10 (偏好学习 + 记忆)

- E: PreferenceLearner + FeatureExtractor 实现
- D: FashionMemoryService（加载/归档/摘要）
- D+E: 偏好学习器集成到 Coordinator 后处理

### Day 11-14 (集成 + 打磨 + 演示)

- F: VTON 集成（如果就绪）+ 格式化完善
- A: MCTS 搜索引擎（时间充裕则加）
- 全队: 异常处理 / Prompt 调优 / 规则扩充 / 边界测试
- Day 14: 演示准备（3-5 条用例 + 录屏 + README）

---

## 六、风险评估

| # | 风险 | 概率 | 影响 | 应对 |
|---|------|------|------|------|
| 1 | LLM 调用超时（管道总延迟 >30s） | 中 | 用户等待久或超时 | 每步独立 timeout + 管道总超时 30s + 降级到当前最佳部分结果 |
| 2 | Agent JSON 解析失败 | 高 | Agent 间传输出错 | Jackson lenient + 失败重试一次 + 默认值替代 |
| 3 | RAGFlow 部署/运维故障 | 中 | 知识层不可用 | 跳过知识层，规则引擎 + LLM 自身知识兜底 |
| 4 | RAGFlow 检索质量差（种子数据不足） | 高 | 知识增强不明显 | Day1 开始标数据；效果差则规则优先 + prompt 工程补偿 |
| 5 | 4 次 LLM 调用总延迟高 | 中 | 用户体验差 | Critic+Trend 真并行 + 微信分片回复（先回文字，再补图片）|
| 6 | 偏好学习反馈数据不够 | 低 | 权重不收敛 | 初始化均等权重 + 准备 20 条模拟反馈做预热 |
| 7 | VTON 不可用 | 中 | 视觉缺失 | 纯文字方案作为 fallback |
| 8 | MCTS 太慢 | 低 | 延迟增加 | 限制迭代 20 + 深度 4 + 超时 10s，时间不够直接砍掉 |
| 9 | 并发用户多导致 LLM 限流 | 低 | 管道失败 | 引入滑动窗口限流（复用已有 ILinkRateLimiter）+ 队列缓冲 |
| 10 | 接口进度不同步 | 中 | 集成返工 | D 每天同步接口 + Day7 强制集成窗口 |

---

## 七、简历建议

**技术关键词**:

- Multi-Agent LLM Collaboration（隔离协议 + 并行编排 + 确定性降级）
- RAGFlow + Domain-Specific RAG（Query Decomposition + Hybrid Search）
- MCTS + LLM Evaluation
- Bradley-Terry Preference Learning
- Constraint-Based Fashion Rule Engine

**面试展开要点**:

- 多 Agent 隔离设计: "Agent 之间不直接通信，杜绝角色混淆和信息串扰。通过 JSON Schema 强制结构化输出，Coordinator 做唯一裁决点，每步可追踪可审计"
- 真并行 vs 假并行: "Critic+Trend 用 CompletableFuture 做 fork/join，每条链路设独立超时，一条失败不影响另一条。总延迟从 Critic+Trend 降为 max(Critic, Trend)"
- RAGFlow 替代自建 RAG: "避免手搓 BM25 + Embedding + Vector DB + Reranker 四层维护成本，RAGFlow 内置文档解析、混合检索和 rerank，非技术人员也可通过 UI 管理知识库"
- 多层降级链: "从 RAGFlow -> Stylist -> Critic -> Trend -> Coordinator -> 规则引擎，每一层都有精确的失败处理策略。最坏情况是 SQLite 安全兜底表——零 LLM 调用，保证用户永远不空等"
- 偏好学习不用 fine-tune: "项目周期 2 周，缺反馈数据，Bradley-Terry 在线更新更轻量可解释"
- MCTS: "穿搭组合是显式搜索空间，MCTS 比 LLM 一次生成更系统地探索多路径，UCB 平衡探索和利用"

---

## 八、性能优化记录

### 8.1 参考图并行发送（2026-08-05）

#### 问题

推荐回复时 `FashionAgentService.scheduleReferenceImages` 逐张**串行**下载参考图并发送到微信：
单张（下载 + 加密 + CDN 上传）约 3~4s，3 张串行实测 **14s**，占工具调用总时长的 40%，
直接叠加到用户可见的回复延迟上（实测单次推荐总耗时 56s，其中工具链 34s）。

#### 设计

- 多张参考图彼此独立，无顺序依赖 → 提交到 `fashionAgentParallelExecutor`（虚拟线程池，
  按需创建线程）并发下载 + 发送，总耗时压到**最慢一张**（约 3~4s，预期省 8~10s）；
- 图片在工具返回前已就绪、文本由模型在工具返回后生成，**"先图后文"的顺序保证不变**；
- `@Autowired(required=false)` + `@Qualifier("fashionAgentParallelExecutor")` 注入，
  单测等无 Spring 场景降级为同步串行发送；
- 单张下载仍有 15s 超时兜底、失败只记日志，不影响其余图片与最终文案；
- 发送线程安全依据：iLink 的 `sendImage` 已支持后台线程发送（试衣完成事件即后台推送），
  同一 bot runner 并发发图安全。

#### 改动

`FashionAgentService.java`：
- 新增 `volatile ExecutorService executor` 字段 + `setExecutor` setter（`@Autowired(required=false)`）；
- `scheduleReferenceImages` 的发送循环由同步调用改为 `downloadAndSendReferenceImageAsync`；
- 新增私有方法 `downloadAndSendReferenceImageAsync`（有池 → 提交异步；无池 → 同步降级）。

#### 验证

- 同一条推荐请求对比 `runtime-bot.out.log`：`resolved 3 urls` 之后 3 张
  `Published reference outfit image` 的总跨度由 ~14s 降至最慢一张（约 3~4s）；
- 微信侧仍先收到图片、后收到文案，3 张图均正常送达。

### 8.2 衣橱单品图优先发送（2026-08-05）

#### 问题

用户基于衣橱单品请求搭配（对话："你衣橱里有一件红色条纹T恤…想用它来搭配一套吗？" → 用户"可以帮我搭配一套"）时，
返回了搭配方案并附图片，但**实际收到的是 RAG 公共参考穿搭图（OSS `fashion-reference/outfits` 下的 Look 图），
不是用户衣橱里那件 T 恤自己的图片**，图文不符。

根因：发图链路 `FashionAgentService.scheduleReferenceImages` 只认 RAG 上下文解析出的 `[outfit_XXX]` 参考编号，
完全不感知"推荐基于用户衣橱单品"这一场景，导致用公共 Look 图代替了用户衣橱单品图。

#### 设计

- `consult()` 流程改为**衣橱优先**：先尝试 `scheduleWardrobeItemImage(userId, userInput)`，
  从用户输入匹配衣橱单品（displayName 命中权重最高、颜色/图案加分），匹配到有图单品则通过同一
  `ImageTaskCompletionEvent` 链路发送该单品自己的图片，并**跳过 RAG 参考图**（避免图文不符）；
  未匹配到衣橱单品才回退原参考图逻辑，通用场景（"海边穿什么"）行为不变；
- 依赖注入用 `@Autowired(required=false)` 挂 `FashionVisualPreviewService`（复用现有取衣橱图能力，
  持久化关闭时该 bean 不存在，自动走参考图兜底），不破坏现有构造签名与测试；
- 同一单品复用 `referenceImageSentAt` 去重 map（10 分钟窗口，key 前缀 `:wardrobe:`），
  防 LLM 二次 consult 重复刷图；失败只记日志、不阻断文案；
- 工具描述约束：基于衣橱单品搭配时 `userInput` 必须完整保留单品名称（示例"用红色条纹T恤搭配一套"），
  不得编造 RAG 参考编号；
- 匹配打分收敛为单一有效分支（`userInput.contains(name)` + 颜色/图案加分），删除无效的双向 contains，
  对齐"简约优先"约束：前台零改动、无新增按钮/必填输入、只发 1 张对应单品图不刷屏。

#### 改动

`FashionAgentService.java`：
- 新增 `volatile FashionVisualPreviewService visualPreviews` 字段 + `setVisualPreviews` setter（`@Autowired(required=false)`）；
- `consult()` 中参考图发送改为 `if (!scheduleWardrobeItemImage(...)) scheduleReferenceImages(...)`；
- 新增私有方法：`scheduleWardrobeItemImage` / `bestMatchingWardrobeItem` / `wardrobeMatchScore` /
  `containsToken` / `publishWardrobeItemImage` / `shouldSkipWardrobeImage`，以及日志脱敏 `anonymize`；
- `fashion_consultant` 工具描述补充衣橱单品场景约束。

`FashionAgentServiceTest.java`：新增 2 个用例（发衣橱单品图；匹配到衣橱单品时完全跳过参考图）。

#### 验证

- `FashionAgentServiceTest` 8 个用例全绿，`mvn test-compile` 通过；
- 行为对照：基于衣橱单品 → 只发该单品图 + 方案；通用场景 → 仍发参考穿搭图；
- **实测修正（2026-08-05 日志+数据库定位）**：真实数据里 `color_primary=RED`、`pattern_code=STRIPES` 是**英文编码**，
  首版匹配用 `userInput.contains("RED")` 判断中文输入"红色"，永远匹配不上；且衣橱单品图实际存在
  （`fashion_wardrobe_item_assets` 中 wardrobe_item=1 有 `is_primary=TRUE` 的 asset，源为抠图生成的 PNG）。
  修复：`attributeMatches` 对编码属性枚举用户输入连续片段经 `FashionAttributeNormalizer.token()` 归一后比对
  （"红色"→RED），displayName 改为忽略大小写包含；新增真实编码数据用例（RED/STRIPES + 中文输入）。
- 已知边界：LLM 只传模糊指代（"用衣柜里的t恤搭一套"不带颜色/款式词）时无法精确定位单品，回退参考图兜底。

### 8.3 参考方案整套试穿（2026-08-05）

#### 问题

推荐一套穿搭后用户说"试穿这套/试穿一下吧"，工具默认只试穿 top 一件单品（`pickGarment` 无类型时默认 top），
用户期望整套都换、且最终只收到一张"全部换好"的效果图，而不是多张逐件图。

根因：试衣引擎为**单件渲染**设计（人物模板 + 一件单品图 → 一张效果图），推荐方案的单品图是分开的
（top/bottom/shoes 各自一张）。

#### 设计

按用户意图区分两种语义，整套试穿采用**链式渲染**：
- **未指明单品**（"试穿这套/试穿一下吧"）→ **整套试穿**：默认只取上衣 + 下装两件，提交**一个链式任务**
  （`chain_garments_json` 记录后续件）。后台依次渲染：先穿上衣 → 把该结果图作为新人物图再穿下装；
  **中间结果仅保存为资产、不推送**，最终只把"全套换好"的一张图推给用户；
  连衣裙/连体裤等无独立 top/bottom 的方案退回全部单品链式渲染；
- **明确指明单品**（"试穿上衣/裤子/连衣裙"）→ 单件试穿（`submitOne`），只换那一件；
- 图片全程走内部资产链路：单品图经 `saveGenerated` 存入本地/OSS 资产，MCP 侧通过签名 URL 读取，中间结果不回传微信。

#### 改动

- `db/migration/V22__fashion_tryon_task_chain.sql`：`fashion_virtual_tryon_tasks` 加 `chain_garments_json`（JSON 数组）；
- `FashionTryOnTask`：加 `chainGarmentsJson` 字段，保留 18 参旧构造兼容；
- `FashionTryOnRepository` + `JdbcFashionTryOnRepository`：新增 `submitWithReferenceOutfitChain` 与 `ChainGarment`，
  claim 查询与解析带出 chain 列；
- `FashionVirtualTryOnService`：新增 `submitFullOutfit`（保存全部单品为资产 → 一次链式提交）与 `GarmentInput`；
  `execute` 解析 chain 后逐阶段渲染，中间结果 `saveIntermediate`（存资产不推送），最终才 `succeed` + `publish`；
- `FashionTryOnTools`：`submitFullOutfit` 改为下载 top/bottom 后一次 `tryOn.submitFullOutfit`，回复"完成后会把全套效果图发给你"；
  工具描述引导 LLM：未指明单品时不传 garmentType（整套）、明确单品时才传（单件）。

#### 验证

- `FashionTryOnToolsCallbackTest` + `FashionAgentServiceTest` 共 13 个用例全绿；
- 行为对照：推荐后"试穿这套"→ 一个链式任务，先穿后套、最终一张全套图；"试穿上衣"→ 只换上衣；
  衣橱单品"试试这件"仍走 `virtual_try_on_wardrobe_item`。

### 8.4 空响应自动重试（2026-08-05）

#### 问题

对话请求"根据今天杭州的天气给我来一套穿搭"时，模型偶发返回**空文本响应**（21.5s 后 `EMPTY_RESPONSE`），
`SpringAiChatCompletionsGateway.extractText` 取空文本后直接抛 `EMPTY_RESPONSE`，请求被映射为"抱歉"安全回复，
穿搭链路中断（连试穿都走不到）。且抛异常前无任何诊断日志（finish_reason/tool_calls/usage 均未记录），无法定位。

#### 设计

- **空响应分两类处理**：
  - 响应**含 tool_calls**（工具调用轮）：Spring AI 内部工具循环已继续处理，不属于空响应，不重试（重试会重复执行工具产生副作用）；
  - 响应**无 tool_calls 且文本空**（真空响应，多为偶发）：自动重试一次，仍空才抛 `EMPTY_RESPONSE`；
- 抛出 `EMPTY_RESPONSE` 前记录诊断日志：finish_reason、hasToolCalls、usage，满足后续根因定位。
  （Spring AI 1.1.8 的 `ChatResponseMetadata` 未暴露 finishReason，暂以 `n/a` 占位，诊断以 hasToolCalls+usage 为主）

#### 改动

`SpringAiChatCompletionsGateway.java`：
- `generate` 中空文本时：无 tool_calls → 同参数重试一次；二次仍空 → 记诊断日志后抛 `EMPTY_RESPONSE`；
- 新增 `hasToolCalls`（`AssistantMessage.getToolCalls()` 判空）与 `finishReason` 辅助方法。

#### 验证

- `SpringAiChatCompletionsGatewayContractTest` + `RoutingLlmGatewayTest` 共 7 个用例全绿；
- 偶发空响应自动重试一次，用户不再直接收到"抱歉"；工具调用轮行为不变。

### 8.5 整套试衣一次出图（拼图 + 改 prompt）（2026-08-05）

#### 问题

8.3 的链式渲染需要连续两次调用生图 API（先穿上衣 → 中间结果做底再穿裤子），
耗时长、成功率叠加（任一次失败整套就失败）、依赖中间资产回读。用户反馈自己已在上游
用"上衣在上、裤子在下"的拼图方式展示穿搭，问能否用同一张拼图直接实现整套试衣、一次出图。

#### 设计

复用上游已有的拼图能力，把整套试穿从"链式两次渲染"改为"**拼图 + 单次生成**"：
- `FashionTryOnTools.submitFullOutfit` 改为：取方案中 top + bottom 各一件，下载字节后
  上下拼接成一张穿搭图（上衣在上、下装在下，复用 `FashionAgentService.buildGarmentCollage`），
  作为**单件**服装图提交，类目码 `FULL_OUTFIT`，后台一次调用生图 API 出一张全套效果图；
- MCP 侧 `tryon.py`：`garmentCategory == FULL_OUTFIT` 时使用独立的整套 prompt——
  "图2是一套完整穿搭的拼图（上方上衣、下方下装），请分别完整穿到图1模特身上，保持上下搭配正确"；
- **降级**：方案缺少上/下装（如连衣裙）或拼图解码失败时，退回单件试穿（`submitOne`），
  保证用户仍能拿到上身效果；
- **链式方案下线**：拼图方案验证通过后（2026-08-05 实测"试穿这套"→ 一次生图出全套图），
  链式渲染代码与 `chain_garments_json` 列已安全删除（见 8.5.1）。

#### 改动

- `mcp-server/tools/tryon.py`：`_CATEGORY_LABELS` 加 `FULL_OUTFIT`；新增 `_FULL_OUTFIT_PROMPT`；
  `virtual_try_on` 按类目选择整套/单品 prompt；
- `FashionAgentService.java`：`buildGarmentCollage` 由包私有改为 `public static`（供跨包复用）；
- `FashionTryOnTools.java`：`submitFullOutfit` 改为下载 top/bottom → 拼图 → `submitWithReferenceOutfit`
  单件提交（类目码 `FULL_OUTFIT`）；新增 `buildCollage`/`isTop`/`isBottom`；删除不再使用的 `garmentLabel`；
  工具描述同步改为"整套=上衣与下装拼图一次生成一张全套图"。

#### 验证

- `FashionTryOnToolsCallbackTest` 新增/更新 2 个用例（拼图一次提交 FULL_OUTFIT、拼图失败降级单件 T_SHIRT）全绿；
  `FashionAgentServiceTest` 8 个用例不受影响全绿；
- 行为对照：推荐后"试穿这套"→ 一张穿搭拼图 → 一次生成一张全套上身效果图；
  拼图失败时仍能退回单件试穿；存量链式任务仍可被后台正常处理。

### 8.5.1 链式方案安全删除（2026-08-05）

#### 问题

拼图一次出图方案实测通过后，链式渲染成为死路径：`FashionVirtualTryOnService.submitFullOutfit`
（链式）已无调用方，`execute` 中的 chain 循环、`chain_garments_json` 列与相关持久化代码均不再产生新数据，
继续保留会造成"两套整套试穿实现并存"的理解负担。

#### 设计

安全删除 = **迁移先行终止存量 + 删列，代码整体移除**：
- V23 迁移先 `UPDATE` 把 `chain_garments_json` 非空的存量任务置为 `FAILED`
  （避免后台继续按已删除的链式逻辑渲染），再 `DROP COLUMN chain_garments_json`；
- V22 迁移文件保留原样（Flyway 已应用，改动会触发 checksum 校验失败）；
- Java 侧删除：`FashionTryOnTask.chainGarmentsJson` 字段与 19 参构造、
  `FashionTryOnRepository.submitWithReferenceOutfitChain` + `ChainGarment`、
  `JdbcFashionTryOnRepository` 中对应列/方法/映射、`FashionVirtualTryOnService` 中
  `submitFullOutfit`（链式）、`GarmentInput`、`execute` 链式循环、`saveIntermediate`、`ChainPiece`、`parseChain`、
  以及仅被链式使用的 ObjectMapper。

#### 改动

- 新增 `db/migration/V23__drop_tryon_task_chain.sql`：终止存量链式任务 + 删列；
- 删除上述 Java 链式代码（无测试依赖链式，`FashionTryOnToolsCallbackTest` 已是拼图路径）。

#### 验证

- `mvn test-compile` 通过；`FashionTryOnToolsCallbackTest` + `FashionAgentServiceTest` 全绿；
- 部署后 Flyway 执行 V23（schema 版本 22 → 23），存量链式任务标记失败、列删除；
- 微信端"试穿这套"仍走拼图一次出图（单件提交 `FULL_OUTFIT`），行为不变。

### 8.6 衣橱单品删除（2026-08-05）

#### 问题

用户衣橱单品只能添加、展示、试穿，无法移除错录或不再想要的衣物；
想要"删掉/不要这件/从衣橱移除"时没有对应工具。

#### 设计

采用**软删除（归档）**而非物理删除：
- 表结构 `fashion_wardrobe_items.item_status` 本就约束在 `('ACTIVE','ARCHIVED')`，
  `search_wardrobe`/`show_wardrobe_items`/试穿/语义索引等所有读取均只取 `ACTIVE`，
  归档后单品从展示、检索与推荐中整体消失，无需级联清理；
- 外键安全：试衣任务/推荐方案对衣橱单品为 `ON DELETE RESTRICT`，物理删除会被外键阻塞，
  归档不触碰历史试衣任务与资产，可保留可追溯；
- 删除后同步语义索引：`FashionSemanticIndexService` 消费端本就支持 `operation='DELETE'`
  （单品格归档后从向量库删除），补上 `enqueueDelete` 入队即可，避免已删单品被自然语言检索到；
- 幂等：已是 ARCHIVED 时再次删除返回 false，工具回复"已不在当前衣橱中"。

#### 改动

- `FashionCoreRepository` + `JdbcFashionCoreRepository`：新增 `archiveWardrobeItem`
  （校验归属 → `UPDATE item_status='ARCHIVED'`，返回是否真实变更）；
- `FashionSemanticIndexJobRepository` + `JdbcFashionSemanticIndexJobRepository`：
  新增 `enqueueDelete`（operation='DELETE' 入队，UPSERT 同键冲突覆盖）；
- `FashionCoreService`：新增 `@Transactional archiveWardrobeItem`，归档成功才入队语义索引删除；
- `FashionTools`：新增工具 `delete_wardrobe_item`（必须先经 search_wardrobe 确认编号、
  仅明确删除意图时调用，回复不暴露内部编号）。

#### 验证

- `FashionToolsCallbackTest` 新增 2 个用例（删除成功、重复删除幂等）；
  `FashionCoreServiceSearchTest` 新增 2 个用例（归档触发 enqueueDelete、已归档跳过）；
  连同 `FashionTryOnToolsCallbackTest`/`FashionAgentServiceTest` 全绿；
- 微信端复测："把衣橱里那件XX删掉"→ `delete_wardrobe_item` → 单品从展示与推荐消失；
  语义索引 DELETE 任务异步执行后，自然语言搜索也不再返回该单品。

### 8.7 衣橱单品彻底删除（含 OSS 清理）（2026-08-05）

#### 问题

归档删除（8.6）只做软删除，OSS 图片与资产记录仍保留；用户询问"彻底删除呢"——
期望连图片数据一并清除（不可恢复）。

#### 设计

彻底删除 = **引用预检 + 删行 + 资产记录清理 + 存储对象清理**：
- **引用预检**（防悬空引用）：该单品存在试穿任务（`fashion_virtual_tryon_tasks.wardrobe_item_id`）
  或推荐记录（`fashion_outfit_recommendation_runs.anchor_wardrobe_item_id`、
  `fashion_outfit_recommendation_items.wardrobe_item_id`，三者均为 `ON DELETE RESTRICT`）时
  拒绝删除并提示；
- **删行**：事务内删除 `fashion_wardrobe_items`（级联删 `fashion_wardrobe_item_assets` 关联行）；
- **资产记录清理**：对该单品关联的每个 `asset_versions` 行尝试 `DELETE`，
  被模板/试衣/分析等仍引用的共享资产由 RESTRICT 外键自动保护（捕获 `DataIntegrityViolationException` 跳过保留）；
- **存储对象清理**：事务提交后（事务外）调用 `LocalImageAssetStore.deleteAsset` / `OssImageAssetStore.deleteAsset`
  （override）删除 OSS 对象（metadata 索引 + 全部版本对象）或本地目录；单个资产清理失败仅告警，不阻断主流程；
- 删除后照常入队语义索引 DELETE。

#### 改动

- `LocalImageAssetStore`：新增 `deleteAsset(userId, assetId)`（删目录 + 清当前缓存，幂等）；
- `OssImageAssetStore`：override `deleteAsset`（读 metadata 索引删各版本对象 + 删索引对象）；
- `FashionCoreRepository` + `JdbcFashionCoreRepository`：新增 `purgeWardrobeItem`
  （引用预检 → 事务删行 → 清理无引用资产记录，返回被清理 assetId 列表）；
- `FashionCoreService`：新增 `purgeWardrobeItem`（repository 事务外调 imageStore.deleteAsset + enqueueDelete）；
- `FashionTools`：新增工具 `purge_wardrobe_item`（显式"彻底删除/永久删除"意图才调用，
  与 `delete_wardrobe_item` 归档区分；有试穿/推荐记录时拒绝并提示）。

#### 验证

- `FashionToolsCallbackTest` +2（彻底删除成功、试穿记录拒绝）；
  `FashionCoreServiceSearchTest` +1（purge 调用 repository + enqueueDelete + 逐资产 deleteAsset）；全绿；
- 行为对照："把XX彻底删掉"→ 预检通过则删行 + 删 OSS 对象（不可恢复）；
  "删掉XX"仍走归档（可恢复）；试穿/推荐过的单品彻底删除被拒绝。

### 8.8 加入衣柜流程：抠图提交口头承诺（2026-08-05）

#### 问题

"加入衣柜"流程实测：发图 + "加入衣柜" → `analyze_wardrobe_photo` 正常（候选推送成功），
但用户随后说"确认加入"时，LLM 只口头回复"正在帮你提取…抠图效果发给你确认"，
**没有调用 `submit_garment_cutout`**，抠图任务未真正提交，用户等不到抠图草稿。

根因：`submit_garment_cutout` 描述要求"已经查看识别候选并明确选择要提取的单品"，
过保守——用户说"确认加入"时模型不认为用户完成了"明确选择"，于是只承诺不调工具
（与 8.3 试穿口头承诺同类问题）。

#### 设计

放宽工具描述，把"确认/选择/加入衣橱"表达映射为提交抠图的明确触发：
- 候选推送后用户说"确认/就它了/确认加入/加入衣柜/帮我抠图"→ 默认理解为确认选择该候选，
  **必须调用** `submit_garment_cutout`，不得只口头承诺；
- 工具本身已支持唯一待选候选时 candidateIds 留空自动恢复（`resolveSelectionCandidateIds`），无需改逻辑；
- 明确区分时序：此刻尚无抠图草稿，"确认加入"= 提交抠图；
  抠图完成、用户确认最终草稿后才调用 `confirm_wardrobe_candidate` 入衣橱。

#### 改动

`FashionWardrobeIntakeTools.submit_garment_cutout` 描述重写（仅描述，逻辑不变）。

#### 验证

- `FashionWardrobeIntakeToolsCallbackTest` 全绿（含"唯一候选留空提交"与"多候选拒绝猜测"用例）；
- 微信端复测：候选推送后说"确认加入"→ 应出现 `submit_garment_cutout` 工具调用 → 抠图草稿推送 → 再确认入衣橱。
