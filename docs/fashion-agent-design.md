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
