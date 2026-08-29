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

**位置**: com.example.ykdsummer.ai.fashion.look.FashionAgentService
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
com.example.ykdsummer.ai.fashion.look
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

### 8.9 联网搜索 search_web 恢复（2026-08-05）

#### 背景

`b05453d refactor(agent): limit tools to fashion workflow` 引入 ToolRegistry **默认拒绝机制**：
只暴露标有 `@AgentTool` 的工具方法，无注解一律 `Skipped non-agent tool`。
`McpWebSearchTools`（search_web）、`WebSearchTools`（web_search）、`FashionCatalogTools`
（search_fashion_products）因此全部被排除，微信端问时事类问题模型只能口头拒绝。

按用户确认的方案**只恢复 `search_web`**（保留默认拒绝机制对其他工具生效）。

#### 第一层：工具注册 + 提示词能力声明

- `McpWebSearchTools` 类加 `@AgentTool`（唯一 Java 改动），启动日志由
  `Skipped non-agent tool: search_web` 变为 `Registered tool: search_web`；
- 工具虽注册，模型仍拒绝回答——根因在 `AiProperties.DEFAULT_SYSTEM_PROMPT` 末尾一句
  "当前没有提供的网页、文档、语音、飞书、娱乐或通用信息查询能力，不要假装可以完成"，
  模型据此认定自己没有联网能力。改为：
  "用户询问新闻、热点、赛事结果等需要实时信息的时效性问题时，必须调用 search_web 工具联网搜索后再回答，
  不得因问题超出穿搭范畴而直接拒绝。除 search_web 联网搜索外，没有文档、语音、飞书或娱乐内容查询能力。"

#### 第二层：MCP 搜索源 DuckDuckGo 不可用

工具被模型正确调用（23:41 连续两次 `web_search`），但返回
`duckduckgo_search.DDGS` 实际走必应海外版 `bing.com/search`，302 后解析失败
（国内网络不可用）。`BOCHA_API_KEY` 未配置。

修复：`mcp-server/tools/search.py` 无 key 默认源改为 **cn.bing.com HTML 解析**
（`https://cn.bing.com/search`，HTTP 200 可用）；`_search_bocha` 失败回退由已删除的
`_search_ddg` 改为 `_search_bing_cn`。

#### 第三层：cn.bing 对长口语 query 跑题

模型拼接的 query 常是整句口语（如"今年世界杯冠军是谁 最新世界杯冠军"），
cn.bing 对"今年/最新"等时间修饰词敏感，返回"今年是什么年/日历网"等无关结果，
模型拿不到答案只能回退训练知识（过时结论"2026 世界杯还没举办"）。

修复（`_search_bing_cn`）：
- `_clean_query`：去掉口语提问词（是谁/今年/最新/吗/呢…）与标点；
- `_core_phrase`：提取最长连续中文片段（>=2 字）作为核心词，如"世界杯冠军"；
- 首搜结果没有任何标题包含完整核心词即判定跑题，用核心词重搜一次；
- 实测两个真实 query（"今年世界杯冠军是谁 最新世界杯冠军"、
  "2026 FIFA世界杯冠军 最新世界杯冠军"）均返回「国际足联世界杯冠军_百度百科」等正确结果。

#### 改动

- `McpWebSearchTools.java`：类加 `@AgentTool`（唯一 Java 改动）；
- `AiProperties.java`：`DEFAULT_SYSTEM_PROMPT` 末尾能力声明改写（声明 search_web 能力）；
- `mcp-server/tools/search.py`：搜索源改 cn.bing.com + query 清洗 + 核心词重搜 + 回退修复；
- `mcp-server/server.py`：启动文案"使用 DuckDuckGo"改为"使用 cn.bing.com 免费搜索"。

#### 验证

- 工具注册日志：`Registered tool: search_web (McpWebSearchTools)`；`web_search`/`search_fashion_products` 仍 Skipped（符合只恢复 search_web 的方案）；
- 本地直接调用 `web_search` 验证两个真实 query 返回相关结果；
- 微信端复测待次日进行（当晚已关闭服务）。

### 8.10 能力声明与工具集对齐（2026-08-06）

#### 背景

`DEFAULT_SYSTEM_PROMPT` 手写"有哪些/没有哪些能力"，与 `ToolRegistry` 的 `@AgentTool`
注册结果是两套信息源。8.9 已踩坑：工具 `search_web` 明明注册了，prompt 却仍写
"没有网页查询能力"，导致模型拒绝调用。能力边界应只由工具集推导，消除人工同步。

#### 设计

- `ToolRegistry.capabilityDeclaration()`：从已注册工具名列表动态生成能力声明段——
  "当前可用的工具：a、b、c。除此之外没有文档、语音、飞书、娱乐或通用信息查询能力，不要假装可以完成。"；
  工具集为空时返回空串（不注入误导性声明）；
- 主聊天通道 `SpringAiChatCompletionsGateway` 构建 system prompt 时追加该动态段
  （`resolveSystemPrompt()`，测试环境 toolRegistry 为 null 时不注入，行为不变）；
- `DEFAULT_SYSTEM_PROMPT` 删除静态能力边界句（保留 search_web 触发规则），
  以后增删 `@AgentTool` 工具自动反映到 prompt；
- `OpenAiResponsesGateway` 是不注册工具的纯多模态通道，`instructions()` 补一句静态边界
  "本通道不提供任何工具调用，不要声称具备网页、文档、语音、飞书、娱乐等工具能力"，
  维持其原有"无工具"能力边界。

#### 改动

- `ToolRegistry.java`：新增 `capabilityDeclaration()`（基于 `allToolMeta()`）；
- `SpringAiChatCompletionsGateway.java`：`buildPrompt` 改走 `resolveSystemPrompt()`，
  拼接动态能力声明；
- `AiProperties.java`：删除会漂移的静态能力边界句；
- `OpenAiResponsesGateway.java`：`instructions()` 补无工具通道能力边界句。

#### 验证

- `mvn compile` 通过；`SpringAiChatCompletionsGatewayContractTest` 全绿；
- `ILinkApplicationContextTest` 的 7 个 MCP 连接 Error 在 MCP server 启动后消失；
  剩余 2 个 Failure（图片 client baseUrl、boundedQueue 默认值）经 git stash 对照
  验证为**既有失败**（本地配置与测试默认值断言冲突），与本次改动无关；
- 微信端验证待部署后复测（新增/移除 @AgentTool 后能力声明应自动跟随）。

### 8.11 MCP 连接自愈（2026-08-06）

#### 背景

Spring AI MCP client 自动配置在启动时即连接外部 server（`Client failed to initialize by explicit
API call`），导致两个问题：
- **启动顺序强依赖**：MCP server（8090）未先启动则 Bot 启动失败；
- **断线不自愈**：MCP server 重启后 client 长连接被 reset（`ClosedChannelException` 于
  `PlainHttpConnection.connectAsync`），客户端不自动重连，只能人肉按"先 MCP 后 Bot"重启。

#### 设计

新增 `McpConnectionManager` 自管理 `SyncMcpToolCallbackProvider` 生命周期，并禁用
Spring AI MCP client 自动配置（`spring.ai.mcp.client.enabled=false`）：

- **懒连接**：首次工具调用时才 `McpClient.sync(transport).build()` + `initialize()`，
  Bot 启动不再强依赖 MCP server；
- **调用失败自愈**：`callTool(toolName, jsonArgs)` 统一入口——连接类异常
  （IOException/ConnectException/HttpTimeoutException 等）时关闭旧 client、重建连接并
  重试一次；工具不存在或未配置时返回 `null`（消费方保留原"未配置"提示语义）；
- **自动重连**：`current()` 在 provider 为空（未配置或上次重建失败）时再尝试重建，
  server 恢复后下一次调用自动生效；
- 连接参数复用 `spring.ai.mcp.client.*` 配置（url / endpoint / request-timeout）。

#### 改动

- 新增 `ai/mcp/McpConnectionManager.java`；
- `McpWeatherProvider` / `McpWebSearchTools` / `McpGarmentCutoutService` /
  `McpVirtualTryOnService` / `McpWardrobePhotoAnalyzer`：构造器由注入
  `SyncMcpToolCallbackProvider` 改为注入 `McpConnectionManager`，调用改走
  `mcp.callTool(toolName, jsonArgs)`；
- `application-local.properties`：加 `spring.ai.mcp.client.enabled=false`（注释说明原因）；
- 对应 10 个测试类同步更新：mock `McpConnectionManager` 并委托到原 provider mock
  （`thenAnswer` 转发 findTool+call），断言不变。

#### 验证

- 10 个 MCP 相关测试（McpWeatherProviderTest / McpWebSearchToolsTest / 3 个 Mcp 服务测试 /
  5 个 provider SelectionTest）全绿；
- 实际部署：禁用自动配置后 Bot 启动 9.6s 成功，日志无 `Client failed to initialize`、
  无预连接；`search_web` 工具注册正常；
- 微信端实测（2026-08-06）：首次调用懒连接建立（`MCP 连接已建立`）；
  **重启 MCP server 后 Bot 不重启**，再次搜索「今年世界杯冠军是谁」→ 新 server 09:32:51
  收到 `web_search` 调用并返回结果，模型正确回答「2026 世界杯冠军是西班牙」——
  断线自愈生效（SDK streamable-http 自动协商新 session 重连，manager 失败重建为兜底）。

### 8.12 异步任务重启恢复（2026-08-06）

#### 背景

进程重启时，进行中的异步任务会停在中间状态卡死：调度器不再认领，任务永远无法
完成或失败。逐一核对 4 类异步任务的中断恢复现状：

| 任务 | 状态字段 | 中断任务默认结局 | 是否需要补恢复 |
|------|---------|----------------|--------------|
| 试衣生成 | `task_status` | PROCESSING 卡死 | 已有 `recoverInterruptedTasks()`（ApplicationRunner）|
| 推荐渲染 | `task_status` | PROCESSING 卡死 | 已有 `recoverInterruptedRenders()`（ApplicationRunner）|
| 抠图任务 | `task_status` | PROCESSING 卡死，仅 `expireUnconfirmedDrafts` 到期标 EXPIRED | **本次补齐** |
| 语义/参考索引 | `lease_until` 租约 | `claimPending()` 每次调度先回收过期租约（PROCESSING + lease_until <= now → PENDING），重启后 ≤5s 自动重新认领 | 无需 |

#### 设计

抠图任务表（`fashion_garment_cutout_tasks`）无租约机制：`claimCutoutTask` 只在
`PENDING` 且未过期时认领，认领后置 `PROCESSING` 无自动回收。补一个启动时的一次性恢复：

- `ApplicationRunner` 启动时把中断的 `PROCESSING` 任务重置回 `PENDING`（`claimed_at = NULL`），
  由既有 `@Scheduled` 调度器（pendingCutoutTaskIds）重新认领；
- 已过期的任务随后由 `expireUnconfirmedDrafts` 统一标 `EXPIRED`（职责不变，恢复不越权）；
- 与试衣/推荐渲染的恢复模式一致：仅重置状态，不重算业务，幂等。

#### 改动

- `FashionWardrobeIngestionRepository`：新增 `int recoverInterruptedCutoutTasks()`；
- `JdbcFashionWardrobeIngestionRepository`：实现
  `UPDATE fashion_garment_cutout_tasks SET task_status='PENDING', claimed_at=NULL
   WHERE task_status='PROCESSING'`；
- `FashionWardrobeIngestionService`：暴露 `recoverInterruptedCutoutTasks()`；
- `FashionGarmentCutoutDispatcher`：改为 `implements ApplicationRunner`，`run()` 中执行恢复
  并打日志（复用既有调度，不新增路径）。

#### 验证

- `mvn compile` 通过；`FashionWardrobeIngestionServiceAsyncAnalysisTest` 3/3 绿；
- 语义/参考索引经 `claimPending()` 租约过期回收自愈，已核对无需改动。

### 8.13 检索多样性：降低跨次推荐重复率（2026-08-06）

#### 背景

用户反馈「每次推荐穿搭重复率比较高」。根因是整条推荐链路是确定性的，无任何
打破重复的环节：

- 知识池小（仅 174 套 seed 穿搭），且每次只取 top-5 进 prompt；
- 检索确定性 top-k：RAGFlow 相似度排序 / MySQL FTS `ORDER BY relevance DESC LIMIT 5`，
  同一需求每次命中同一批参考；
- 无历史去重（虽然每次推荐已落库 `fashion_outfit_recommendation_runs`，但检索与
  prompt 均未消费）；
- 模型温度保守，同样输入输出趋同。

#### 设计（方案 A：相关性优先的加权随机）

原则：**在相关域内做多样性，不牺牲匹配度**。先按相关度取较大候选池，再在池内
加权随机抽 top-k——高分命中概率远大于低分（权重 = score² + ε，放大高分段差异），
牺牲的是「9 分 vs 8 分」的微差，换来不同次推荐命中不同穿搭。

- 候选池大小可配（默认 15），相关度下限可配（`min-score`，0 不限制）；
- 候选池小/约束强时自动趋近确定性 top-k（池 ≤ k 则原样返回）；
- 新增开关 `enabled`，关闭即恢复旧确定性行为，可随时回退。

#### 改动

- 新增 `FashionRagDiversityProperties`（`app.fashion.rag.diversity.*`），注册到
  `@EnableConfigurationProperties`；
- 新增 `RetrievalDiversitySampler`：加权随机不放回抽样工具；
- `RagFlowClient`：`retrieve(question, pageSize)` 支持传入候选池大小（原签名保留）；
- `RagFlowKnowledgeService` / `MysqlFtsKnowledgeService`：检索池扩大为
  `max(topK, candidatePool)`，结果经采样器抽回 top-k；
- `application-fashion.properties`：新增三个 diversity 默认配置（环境变量可覆盖）。

#### 验证

- `mvn compile` 通过；
- `FashionAgentServiceTest` / `QueryAnalyzerFeedbackDetectionTest` 15/15 绿；
- 新增 `RetrievalDiversitySamplerTest` 4/4 绿（池小于 k 保序原样返回、池大于 k 精确抽
  k 条且不重复、min-score 过滤、null 安全）。

### 8.14 跨次推荐去重：历史滑动窗口 + prompt 防重复（2026-08-06）

#### 背景

8.13 的加权随机打破了检索的确定性，但系统仍"不记得上次推荐过什么"——同需求下
最近已推过的参考穿搭可能再次被选中。用户对方案选型评估后确认实施 5+4 组合：
历史滑动窗口去重（检索层）+ prompt 防重复指令（生成层）。

#### 设计（方案 5：历史滑动窗口）

- 数据源：`fashion_conversations.reference_outfit_id`（每次推荐命中并落库的 outfit
  编号，V20 已建）；
- 检索前查用户最近 N=5 次命中的 outfit 编号（去重）作为排除集；
- `FashionKnowledgeService` 新增 `retrieveExcluding(query, excludeIds)`：排除集内编号
  不进入候选池（**采样前过滤**，比检索后过滤更优——不浪费候选池）；排除后候选
  不足时返回过滤后的全部，不强凑不相关结果；
- 候选池仍走 8.13 的多样性采样，两层叠加：先排除已推 → 再池内加权随机。

#### 设计（方案 4：prompt 防重复）

- Stylist prompt 增加指令："避免与用户近期推荐过的搭配重复……若仍出现风格雷同
  的条目，优先选差异更大的一套"——作为检索层排除不彻底时的生成层兜底。

#### 改动

- `FashionConversationService`：新增 `findRecentReferenceOutfits(userId, limit)`
  （最近 N 次非空编号，distinct，兼容 outfit_002 前缀归一化）；
- `FashionKnowledgeService`：新增 `retrieveExcluding` 默认方法（默认过滤语义），
  两个实现类 override 为"采样前过滤"；
- `RagFlowKnowledgeService` / `MysqlFtsKnowledgeService`：`retrieve(query)` 委托
  `retrieveExcluding(query, Set.of())`，排除逻辑与多样性采样同处；
- `AgentCoordinator`：Step 2 检索前注入排除集（窗口常量
  `RECENT_RECOMMENDATION_WINDOW=5`），日志打印排除数量；
- `AgentPrompts`：Stylist 增加防重复指令。

#### 验证

- `mvn compile` 通过；
- `FashionAgentServiceTest` / `QueryAnalyzerFeedbackDetectionTest` /
  `RetrievalDiversitySamplerTest` / `FashionAgentExecutorConfigTest` 20/20 绿；
- 接口默认方法保证无排除场景行为不变（`retrieveExcluding(query, 空集)` = `retrieve`）。

### 8.15 删除 xxx_3_bottom 穿搭（2026-08-06）

#### 背景

数据源中部分套装带 `_3_bottom` 下装单品，用户要求清理。规则（用户确认）：
- 完整 1-3 结构（如 `_1_top` + `_2_top` + `_3_bottom`）→ 删除多余单品 `_2_top`，保留 `_1_top` + `_3_bottom`（14 套）；
- 仅有 2-3 结构（缺主单品）→ 整套全链路删除（13 套）；
- 原始仅 `_1_top` + `_3_bottom`（缺 _2）→ 保留（6 套，用户确认）。

#### 改动

- `data/image_urls.json`：移除 13 套（029/030/041/052/057/059/060/061/063/077/091/108/112），
  14 套（070/071/075/081/099/106/109/114/115/116/118/151/271/285）各删 `_2_top` → 161 套；
- `data/xiaohongshu_fashion_seed.json`：同步删 13 条 → 161 条；
- `data/fashion_docs/`：删除 13 个 `outfit_XXX.md` → 161 个；
- RAGFlow：DELETE 13 个文档（total 174 → 161）；
- OSS：删除 13 套 47 个残留图片文件（fashion-reference/outfits 目录 174 → 161）；
- MySQL `fashion_conversations.reference_outfit_id`：清空指向已删套装的残留（028×1、108×3）；
- 备份：`data/image_urls.json.bak_20260806`、`data/xiaohongshu_fashion_seed.json.bak_20260806`；
- 重启 Bot 后验证：fashion_docs / image_urls / seed / RAGFlow 全部 161，微信端试穿（270/045）正常出图。

### 8.16 试穿编号校正：Coordinator 幻觉编号回退 RAG 命中（2026-08-06）

#### 问题

用户说"试穿一下"时工具收到 `outfit=028`，但 028 在 image map / seed / fashion_docs 均不存在
（Coordinator 模型幻觉编号），`virtual_try_on_reference_outfit` 查无单品图 → 返回"没有可用于
试穿的单品图"，只能引导去试穿衣橱。发图链路（`resolveReferenceImages`）已做过同样的
Coordinator→RAG 回退，但 `persistConversation` 存进 `fashion_conversations.reference_outfit_id`
的仍是幻觉编号 028，主模型"试穿"指代消解读到无效编号。

#### 设计

`AgentCoordinator.persistConversation` 保存编号前校正（与发图链路同一策略）：
- Coordinator 编号在参考图库中**有单品图**（`imageResolver.garmentsFor` 非空）→ 原样保存；
- 无效（LLM 幻觉）→ 回退 RAG 上下文第一个 `[outfit_XXX]` 标记的编号（有效则保存），保证
  发图与试穿使用同一套有效编号。

#### 改动

- `AgentCoordinator`：注入 `ReferenceImageResolver`；新增 `resolveEffectiveOutfitId` /
  `hasUsableGarments` / `extractOutfitIdFromRagContext` / `normalizeOutfitId`；
  `persistConversation` 用校正后的编号回填；
- 清理 DB 残留 `reference_outfit_id=028/108`。

#### 验证

- `FashionAgentServiceTest` 全绿（构造签名同步更新）；
- 微信端两轮验证："试穿一下"均收到有效编号（270/045），参考穿搭试衣 source=reference 成功出图。

### 8.17 推荐工具漏调自动补调（2026-08-06）

#### 问题

用户"推荐一套适合今天在杭州打羽毛球的穿搭"，主模型（qwen3.7-max）先调 `get_current_weather`
后**直接输出穿搭文字**，未调用 `fashion_consultant`（LLM 工具调用不稳定，违反 prompt
"查完天气必须继续调用该工具"指令），导致推荐无参考图。

#### 设计（网关层兜底）

`SpringAiChatCompletionsGateway` 在模型最终文本生成后自动补调：
- `BoundedToolCallingManager` 记录本轮实际调用过的工具名（`AssistantMessage.ToolCall.name`）；
- 满足全部条件才补调：①未调用 `fashion_consultant`；②未调用任何穿搭域工具
  （衣橱/试穿/抠图等，避免与已进行的操作冲突）；③用户输入命中穿搭推荐意图
  （搭配/穿什么/面试穿/场合等强触发词；"推荐"需与穿搭语义词共现，排除"推荐一部电影"；
  排除衣橱/试穿/图片操作消息，`(?<!面)试穿` 避免误伤"面试穿"）；
- 补调方式：反射调用 `fashion_consultant(prompt)`，把带参考图的方案追加到回复；
- 补调异常只记日志，不影响原文本。

#### 改动

- `BoundedToolCallingManager`：新增 `calledTools` ThreadLocal + `calledToolNames()`；
- `SpringAiChatCompletionsGateway`：新增 `maybeAutoConsult` / `hasFashionConsultIntent`
  与意图/排除正则；
- 新增测试 `SpringAiChatCompletionsGatewayAutoConsultTest`（推荐意图命中、衣橱/试穿/非穿搭排除）。

#### 验证

- 单测全绿；微信端回归：模型正常调用时补调不触发，行为不变。

### 8.18 推荐推送顺序：先整套图 → 参考拼图 → 文本（2026-08-06）

#### 问题

8.1 假设"图片在工具返回前就绪、文本晚于图片，先图后文顺序不变"，但实测第二轮（045）
collage 拼图（需下载两张单品 + 拼接 + 上传 CDN）慢于文本生成（约 3s），导致
`overview → 文本 → collage`，拼图落在文本之后，顺序错乱。首轮（270）模型文本生成慢（9s）恰好掩盖了该问题。

#### 设计（用户确认：并行 + 等图，不串行）

- 图片仍与文本生成**并行**：`scheduleReferenceImages` 提交发送单元到线程池后立即返回，
  不阻塞工具（避免 8.1 之前逐张串行 + 文本串行的双重延迟）；
- 新增 `ReferenceImageSendGate`：图片发送完成信号按 userId 登记；
- `AiChatService` 在 gateway 返回文本后、真正发送前 `await(userId, 10s)`——
  无登记立即返回；有登记等图片发完（超时 10s 放弃，不阻塞回复）。

#### 改动

- 新增 `ReferenceImageSendGate`（track/await，ConcurrentHashMap + CompletableFuture）；
- `FashionAgentService`：`submitSendUnit` 返回 Future，`scheduleReferenceImages` 聚合为
  `CompletableFuture.allOf` 后 `imageSendGate.track`（`imageSendGate` 为空时跳过，兼容单测）；
  删除 `awaitAllReferenceImages` 与 `sendUnitAsync`；
- `AiChatService`：setter 注入 `ReferenceImageSendGate`；generate 成功后
  `imageSendGate.await(userId, 10_000)`。

#### 验证

- `FashionAgentServiceTest` / `AiChatServiceTest` / 网关测试全绿；
- 日志顺序保证：`Published overview` → `Published collage` → `Sending text message`；
- 耗时：图片与文本并行，文本到达时间几乎不受影响（仅等图，最多 10s 上限）。

### 8.19 修复 8.18 引入的 ArrayStoreException（2026-08-06）

#### 问题

8.18 部署后首轮真实请求（"我要去参加婚礼,你帮我搭一套"）在完整 pipeline 跑通后仍失败：
`Fashion pipeline unexpected error: arraycopy: element type mismatch ... java.util.concurrent.CompletableFuture`，
用户收到兜底文案。8.18 仅通过单测（executor=null 走同步分支），未覆盖真实 executor 路径。

#### 根因

`submitSendUnit` 在 executor 装配时返回 `pool.submit(...)` 的 `FutureTask`（非 `CompletableFuture`），
而 `scheduleReferenceImages` 用 `sentFutures.toArray(new CompletableFuture[0])` 聚合 →
`ArrayStoreException` → 外层 catch → 整个 `fashion_consultant` 工具失败。

#### 修复

- `submitSendUnit` 返回类型 `Future<?>` → `CompletableFuture<?>`，executor 分支改用
  `CompletableFuture.runAsync(() -> sendUnit(...), pool)`；
- `sentFutures` 列表类型同步改为 `List<CompletableFuture<?>>`，`allOf` 聚合类型安全。

#### 验证

- 单测全绿（executor=null 同步分支不受影响）；
- 已重新打包重启，待微信端用正式场景请求复验完整 pipeline + 图片推送。

### 8.20 试穿漏调工具自动补调 + Prompt 强化（2026-08-06）

#### 问题

用户发"试穿一下"（针对刚获得的推荐方案 264），主模型 qwen3.7-max 仅回复
"正在为你试穿…稍等一下"承诺文案，未调用 `virtual_try_on_reference_outfit`，
数据库无新任务、用户等不到效果图。与 8.17"推荐漏调"同源：LLM 工具调用不稳定。

#### 方案（用户确认：A+C 组合）

- **A 网关自动补调**：`SpringAiChatCompletionsGateway` 新增 `maybeAutoTryOn`——
  本轮未调用任何穿搭域工具 + 消息命中试穿意图（`(?<!面)试穿|穿一下|穿穿|上身效果|穿上看看|试试|试一下|试一试|试下`）
  + 排除衣橱/疑问/图片类消息时，取 `FashionConversationService.findLatestReferenceOutfit(userId)`
  反射补调 `virtual_try_on_reference_outfit(id, null)` 整套试穿，把后台任务提示追加到回复；
- **C Prompt 强化**：`DEFAULT_SYSTEM_PROMPT` 试穿规则改为"必须在本轮实际调用试穿工具并提交后台任务，
  严禁只回复承诺文案而漏调工具（漏调会导致用户永远收不到效果图）"。

#### 改动

- `SpringAiChatCompletionsGateway`：`TRY_ON_TOOL`/`TRY_ON_INTENT`/`TRY_ON_EXCLUDE` 常量、
  `maybeAutoTryOn`/`hasTryOnIntent`、`@Autowired(required=false)` 注入 ConversationService（测试构造不受影响）；
- `AiProperties.DEFAULT_SYSTEM_PROMPT`：试穿规则强化；
- 测试：`SpringAiChatCompletionsGatewayAutoConsultTest` 新增 `detectsTryOnIntent`/`ignoresTryOnNonExecutionAndInterview`。

#### 验证

- 相关测试全绿；已打包重启，待微信端复验"试穿一下"能实际创建任务并出图。

### 8.21 试穿路由错误修复：衣橱单品被误路由到推荐方案（2026-08-06）

#### 问题

用户先"看看衣柜"（2 件：灰色T恤 id=4、红色T恤 id=1），随后"试一下灰色T恤"，
qwen3.7-max 却调用 `virtual_try_on_reference_outfit outfit=238, type=top`（238 是白色Polo衫），
效果图穿错衣服。工具描述中已有路由规则但模型未遵守，且 8.20 网关兜底只覆盖"漏调"
（模型已调穿搭域工具即跳过），错调路由被坐实。

#### 方案（用户确认：A+B）

- **A 系统 Prompt 强化**：`DEFAULT_SYSTEM_PROMPT` 试穿规则补充——用户提到衣橱具体单品
  （灰色T恤/红色T恤/那件XX/衣柜里的XX，或刚查看/筛选过衣橱）时，必须先 `search_wardrobe`
  定位 wardrobeItemId 再调 `virtual_try_on_wardrobe_item`，严禁把历史推荐 outfit 编号当衣橱单品
  传给 `virtual_try_on_reference_outfit`；
- **B 工具描述互斥强化**：两个试穿工具 description 各自补互斥规则（衣橱单品 → wardrobe_item，
  reference_outfit 只装参考库衣服），从模型工具选择源头纠偏。

#### 改动

- `AiProperties.DEFAULT_SYSTEM_PROMPT`：试穿路由规则强化；
- `FashionTryOnTools`：`virtual_try_on_wardrobe_item` / `virtual_try_on_reference_outfit` description 补充互斥说明。

#### 验证

- 编译通过；已打包重启，待微信端复验"试一下灰色T恤"应调用 wardrobe_item 并穿灰色T恤出图。

### 8.22 批量优化：工具子集 / 文案如实描述 / 错调兜底 / 上下文精简 / 测试补强（2026-08-06）

基于 8.17-8.21 连续暴露的"模型工具调用不稳定 + 文案与图不符 + 回归未被单测拦截"，
按用户确认的优先级实施 5 项优化：

#### 优化1：按意图动态提供工具子集

- **背景**：38 个工具对 qwen3.7-max 决策负担大（首轮决策 ~20s、漏调/错调反复）。
- **改动**：
  - `ToolRegistry` 新增分组 `TOOL_GROUP`（core/wardrobe_view/wardrobe_intake/tryon/reminder）+ `toolBeansForGroups`/`toolNamesForGroups`；
  - `SpringAiChatCompletionsGateway.toolsForPrompt(prompt)` 按意图路由：推荐→核心+试穿、衣橱/试穿→+衣橱查看、入库→+抠图入库、提醒→+提醒组、默认→仅核心（11 个）；
  - 能力声明同步按子集生成，避免"声明 38 个、实际只提供子集"。
- **预期收益**：推荐轮工具 38→17、默认轮 38→11，降低漏调/错调率与首轮决策耗时。

#### 优化2：Stylist/Coordinator 强制如实描述参考单品

- **背景**：模型把"建议替换"写成事实（Polo衫→"亚麻衬衫"、黑T恤→"白衬衫"），文案/发图/试穿三者脱节。
- **改动**：`AgentPrompts.STYLIST` 与 `COORDINATOR` 新增铁律——方案描述必须与 [outfit_XXX] 真实单品完全一致；
  替换建议只能放 practicalTips/selectionReasoning 并标注"建议"，不得改写 refinedOutfit 事实描述。

#### 优化3：网关衣橱单品错调兜底

- **背景**：8.20 兜底只覆盖"漏调"；"错调"（衣橱单品意图被路由到 reference_outfit）无法兜住。
- **改动**：`SpringAiChatCompletionsGateway` 新增 `maybeCorrectWardrobeMisroute`——检测到
  衣橱单品意图（试穿词+单品词）且模型调用的是 reference_outfit 时，追加纠正提示。
- **限制**：工具已执行无法撤销，仅提示用户重发；真正的路由纠正在优化 1/8.21 的 prompt 层。

#### 优化4：Coordinator 上下文精简

- **背景**：Coordinator prompt 6949 字符（RAG 上下文 3311 占 48%），裁决轮 8.3s。
- **改动**：`AgentCoordinator.compactRagContext` 保留 `[outfit_XXX]` 编号 +【完整搭配】，
  丢弃【单品详情】冗长内容后传入 Coordinator（Stylist 仍用完整上下文）。
- **预期收益**：Coordinator prompt 约减 27%，首 token 更快。

#### 优化5：测试补充真实 executor 分支

- **背景**：8.19 ArrayStoreException 未被单测拦截（单测只走 executor=null 同步分支）。
- **改动**：`FashionAgentServiceTest.schedulesReferenceImagesWithRealExecutorDoesNotThrowArrayStore`
  装配真实单线程池 + mock `ReferenceImageSendGate`，验证不抛异常且 track 被登记。

#### 验证

- `SpringAiChatCompletionsGatewayAutoConsultTest`（新增路由/衣橱单品意图测试）、
  `FashionAgentServiceTest`、`AiChatServiceTest` 全绿；
- 已打包重启（PID 27092），待微信端复验：推荐轮工具决策耗时下降 + "试一下灰色T恤"正确走 wardrobe_item。

#### 8.22 补充：入库路由正则漏"衣柜" + 照片入库被拒修复（2026-08-06）

复验中发现「帮我加入衣柜」失败：
- **路由 bug**：`INTENT_WARDROBE_INTAKE` 只匹配"加入衣橱"，用户说"加入衣柜"未命中 → 入库工具组未挂载，
  模型空响应重试后回复"推荐款无法入库"拒绝。
- **模型混淆**：把用户刚发照片的牛仔裤当成历史推荐方案的衣服，拒绝 analyze_wardrobe_photo 入库。
- **修复**：
  - 路由正则补"衣柜"类表达（放进衣柜/加入衣柜/帮我加入/这张图…衣柜）；
  - `DEFAULT_SYSTEM_PROMPT` 补强：用户发照片后说"加入衣柜/衣橱/入库"指的就是刚发/刚识别的照片，
    必须调 analyze_wardrobe_photo 拆件入库，严禁以"推荐款/参考款无法入库"拒绝；
  - 测试补「帮我加入衣柜」应命中入库组。
- 已打包重启（PID 30980），待微信端复验照片入库链路。

#### 8.22 补充 2：候选确认路由盲区——"只要牛仔裤"无抠图工具（2026-08-06）

用户识别出 3 件候选（牛仔裤/配饰/T恤）后回复「只要牛仔裤」，路由只给 `[core]`（6 beans），
抠图工具未挂载 → 模型只回复"正在为你提取蓝色牛仔裤"却未真正调 submit_garment_cutout，
用户实际等不到草稿（日志 14:18:53 → 无任务创建）。
- **根因**：`INTENT_WARDROBE_INTAKE` 正则未覆盖"候选确认/选择"类表达（只要/就要/选第一件/选这件/都要/就这件/抠图吧）。
- **修复**：路由正则补 `只要|就要|选第一件|选这件|选.*件|都要|确认入库|就这件|这件可以|抠图吧`；
  `routesToolGroupsByIntent` 补 3 条断言（只要牛仔裤/选第一件/这几件都要 → 命中入库组）。
- 本轮一并打包部署。

#### 8.23 抠图中发新套装丢失信息：跨照片自动定位 + 新照片未先识别（2026-08-06）

用户「只要牛仔裤」后（候选仍处于 PENDING_SELECTION）又发来一张新套装照片并说「加入衣柜」，
模型直接调 `submit_garment_cutout` 且 candidateIds 留空 → 自动定位到**上一张照片的牛仔裤**候选
（唯一待选），新套装照片从未被 analyze，信息整体丢失（日志 14:21:26 → 提交的是旧照片候选）。
- **根因 A（静默选错）**：`resolveSelectionCandidateIds` 按全局"唯一待选候选"自动定位，不区分来源照片；
  用户在两张照片间流转时会把旧照片候选当作新照片目标。
- **根因 B（新照片未识别）**：模型收到新照片+"加入衣柜"直接走 submit，跳过 analyze_wardrobe_photo。
- **修复**：
  - `submit_garment_cutout` 新增可选 `imageAssetId` 参数：指定后候选限定到该照片；
    该照片无候选时返回"这张照片还没有识别候选，请先调用 analyze_wardrobe_photo 识别后再提交抠图"，
    形成"先识别再抠图"的自纠正闭环；
  - 自动定位拒绝跨照片：候选来自多张照片时抛"候选来自多张照片，请指明要处理哪张照片的哪件单品"，
    IllegalStateException 消息透出给模型（提交抠图失败：…）；
  - `resolveReviewCandidateId`（确认入衣橱）同样拒绝跨照片待确认草稿，
    `confirmFailureMessage` 增加"多张照片草稿待确认，请先指明哪件"引导；
  - 工具描述 + `DEFAULT_SYSTEM_PROMPT` 补强：抠图中发新照片是新的入库对象，必须先对新照片
    analyze_wardrobe_photo，严禁跨照片自动定位；
  - 测试补 3 个：按 imageAssetId 只提交指定照片候选 / 跨照片无显式目标拒绝 / 未识别照片引导先 analyze。
- 修复后：用户在抠图进行中发新套装，提交抠图必须携带新照片的 img_ 或候选编号，
  系统不再静默复用上一张照片的候选。

#### 8.24 照片入库漏调兜底：模型只承诺"正在提取"未调 analyze_wardrobe_photo（2026-08-06）

复验照片入库时再次复现：用户发照片 →「请描述这张图片」（inspect_image）→「加入衣柜」，
模型**未调任何入库工具**直接回复"正在为你提取这套穿搭"（15:13:06 日志，与 15:03:31 同）。
- **根因**：`inspect_image` 返回"识别结果…已登记到图片元数据"误导模型认为照片已被识别，
  后续「加入衣柜」被模型当作"确认抠图"只回承诺文案；8.22 的 Prompt/路由修复不依赖模型遵守，
  无法兜住漏调。
- **修复**：网关新增 `maybeAutoWardrobeIntake` 兜底（与 8.17 maybeAutoConsult / 8.20 maybeAutoTryOn 同模式）：
  - 判定 `shouldAutoWardrobeIntake`：本轮未调任何穿搭域工具 + 用户输入（剥离内部上下文）命中
    `INTENT_WARDROBE_INTAKE`；
  - 命中后取最近上传照片（`LocalImageAssetStore.latest(userId, null)`）反射补调
    `analyze_wardrobe_photo`，把识别任务真正提交后台，候选生成后自动推送；
  - 已调过入库/穿搭域工具则不重复补调。
- 测试补 3 个：意图判定（含内部上下文剥离、已调工具排除）+ 完整补调执行（mock 真实
  FashionWardrobeIntakeTools 反射调用，验证 submitPhotoAnalysis 被调）+ 已调工具跳过。
- **首轮部署后复验仍失败**（15:34:22「帮我加入衣柜吧」）：模型回复"参考款无法加入衣橱"拒绝，
  且兜底未触发。定位两处：
  1. `imageStore.latest(userId, null)` 内部 `metadata(null)` 被 `validAssetId` 拦截永远返回空
     → 兜底取不到图片编号静默跳过；改用 `recent(userId, 1)` 按时间取最近照片；
  2. 模型把用户刚发照片误当"参考款/推荐款"拒绝（8.22 Prompt 未生效）→ 兜底补调成功时，
     若模型原文含"无法入库/不能加入/参考款/推荐款"等拒绝特征，用识别提示**替换**拒绝文案，
     避免用户看到矛盾回复。
- 已打包重启（PID 6652），待微信端复验「发照片→加入衣柜」不再空承诺或误拒。

#### 8.24 补充：候选确认轮兜底方向修复 + 内部编号泄露防护（2026-08-06）

复验发现 MCP 修复后识别链路通了（15:54:18 识别 → 15:54:25 候选推送成功），但用户
「确认」候选时模型空响应 → 兜底错调 `analyze_wardrobe_photo`（返回已有候选列表）→
**内部 candidateId 原样发给了用户**，且抠图未真正提交。
- **根因 A（兜底方向错）**：用户"确认"对应提交抠图，兜底却重新识别；
- **根因 B（正则缺"确认"）**：`INTENT_WARDROBE_INTAKE` 无单独"确认"表达，生产靠内部上下文
  残留词才误命中（测试用纯"确认"不命中）；
- **根因 C（结果外泄）**：analyze 返回的"内部候选"文案是给模型看的，被兜底原样追加到用户消息。
- **修复**：
  - 正则补 `确认|确认一下|就它了|就它|就这件吧|同意`；
  - 兜底按照片候选状态分流：该照片有 PENDING_SELECTION+READY 待选候选 → 补调
    `submit_garment_cutout(candidateIds=null, imageAssetId=照片)` 提交抠图（8.23 的按照片定位生效）；
    无待选候选 → 才补调 `analyze_wardrobe_photo`；
  - `sanitizeWardrobeToolResult` 兜底结果清洗：剥离"内部候选"等仅供模型的结构，防编号泄露。
- 测试补 2 个：已有待选候选 → 补调 submit 且不外泄；analyze 返回候选列表 → 剥离。14 用例全过。
- 已打包重启（PID 31496），待微信端复验「确认」能真正提交抠图。

#### 8.25 MCP 连接稳定性 + "试穿一下"被误判入库修复（2026-08-06）

**MCP 连接稳定性（14:55 与 15:41 两次复现）**：识别 MCP 调用已发出、MCP 侧完成，Java 却收不到返回
（长连接偶发挂起）→ 判定 Java↔MCP 链路问题。
- **修复**：`McpConnectionManager.rebuild()` 的 HttpClient 追加
  `customizeRequest(timeout)` 请求级超时兜底（防无限阻塞）+ `customizeClient(HTTP_1_1)`
  强制 HTTP/1.1（绕开 hypercorn h2c 长连接挂起）。
- 修复后识别/抠图 MCP 调用 7-10 秒内稳定返回。

**"试穿一下"被误判入库（16:12:21）**：用户刚收到 048 少年风推荐后发「试穿一下」，
模型只回"已经提交试穿任务了"未调任何工具，随后兜底**错调 analyze_wardrobe_photo**，
用户收到"正在识别图片中"的废话，试穿实际未提交（日志 16:12:39）。
- **根因 A（试穿兜底失效）**：`maybeAutoTryOn` 用**未剥离**内部上下文的 prompt 调 `hasTryOnIntent`，
  内部块含"衣橱/衣柜"被 `TRY_ON_EXCLUDE` 挡掉 → 试穿兜底不触发；
- **根因 B（入库兜底误触发）**：`shouldAutoWardrobeIntake` 对剥离后文本仍命中 intake
  （剥离异常残留"确认"等词，或内部块格式漂移），把纯试穿误判成照片入库；
- **根因 C（路由侧同样受影响）**：16:08/16:10/16:11 各轮（含纯推荐"推荐一套少年风穿搭"）
  均挂着 intake 组——只要用户有 PENDING 候选，候选块含"确认"词就命中 intake 正则，
  仅因模型正常调了工具（called 非空短路兜底）才未暴雷。
- **修复**：
  - 四个兜底判定（maybeAutoConsult / maybeAutoTryOn / maybeCorrectWardrobeMisroute /
    shouldAutoWardrobeIntake）统一先 `stripInternalContext(prompt)` 再判意图；
  - `shouldAutoWardrobeIntake` 增加纯试穿排除：剥离后命中 `TRY_ON_INTENT`（试穿|穿一下|试试|试一下…
    用 TRY_ON_INTENT 而非 hasTryOnIntent，后者带"照片/图片"排除词、剥离残留会误判）→ 直接返回 false，
    即使内部块剥离异常残留"确认/照片"词也不走入库链路；候选确认/入库表达（确认/就要牛仔裤/选第一件/
    抠图/就这件吧）不受影响，仍正常触发兜底；
  - 加诊断日志：`ROUTE-DIAG`（prompt 含内部块却仍命中入库组，打印剥离结果）与
    `AUTO-INTAKE-DIAG`（兜底触发时打印 called + 剥离文本），下次复现可直接定位残留词来源。
- 测试补 4 个：真实 contextFor 三块（含嵌套"见[内部衣橱流程状态]"）完整剥离验证、剥离异常
  （未闭合块残留"确认"）下纯试穿仍不触发入库兜底、候选确认/入库表达兜底不受破坏、路由不挂入库组。
  18 用例全过。已打包重启（PID 9800）。

#### 8.25 补充：真正的根因——文档指令模板污染路由（2026-08-06）

复验 8.25 修复后仍发现：用户发「推荐一套少年风穿搭」，路由依然挂 `[wardrobe_intake, core, wardrobe_view, tryon]`
（新增的 `ROUTE-DIAG` 日志暴露），且模型推荐成功后回复"已经提交试穿任务了"——**推荐被说成试穿**。
- **根因**：`ILinkReplyService` 把**所有纯文本消息**都交给 `FileInstructionService.process`，
  `buildPrompt` **总是**把 `DOCUMENT_TOOL_INSTRUCTION` 模板拼到用户消息前：
  模板中的"用户**只要求**转成 PDF…"含连续"只要"二字，被 `INTENT_WARDROBE_INTAKE` 正则的「只要」
  误命中 → 每轮纯文本（含推荐/试穿）都挂上入库工具组 → 模型输入被"## 当前文档与工具规则"污染，
  把"推荐穿搭"输出成"试穿任务/识别图片中"（16:12:21 与 16:42:45 同源）。
- **修复**（两层）：
  1. `FileInstructionService.buildPrompt`：`sourceFile == null`（无文档上下文）时**直接透传用户指令**，
     不再拼接文档指令模板；文档会话（用户发过文件后）仍带完整规则。ILinkReplyService 链路与
     artifact（文档/图片/音频）转换能力保持不变；
  2. 路由正则防御：`只要` → `只要(?!求)`，排除"只要求"式误命中；真实候选选择
     "只要牛仔裤/只要这件"不受影响。
- 测试补 2 个：文档指令包装文本（含"用户只要求"）不命中入库组 + "只要牛仔裤"仍命中。
  AutoConsultTest 19 用例 + ILinkReplyServiceTest 15 用例全过。已打包重启（PID 33988）。

#### 8.26 参考图发送：叠穿方案只发 2 张（2026-08-06）

复验「推荐一套适合去搭讪的穿搭」时，outfit 148（叠穿：白色条纹衬衫叠穿黑色针织上衣）一次发回 3 张图
（overview + 拼图 + 单件上衣），用户觉得冗余。
- **根因**：outfit 148 有 4 张参考图（overview + 2 件 top + 1 件 bottom）。`planSendUnits` 只把
  top①+bottom 合并拼图、overview 单独发，**第二件 top（148_4_top）无法配对 → 被当独立单品单独发**。
- **修复**：`planSendUnits` 新增 `isGarmentImage` 过滤——top/bottom 之外的**所有分割单品图**
  （叠穿第二件上衣、鞋、配饰）不再单独发；overview 等非分割图仍单独发。叠穿方案统一为 2 张
  （整体图 + top①+bottom 拼图），普通方案不变。
- 测试补 1 个：`layeredOutfitWithTwoTopsSendsOnlyOverviewAndCollage`（148 四 url → 2 次发送，
  第二件 top 不单发）。FashionAgentServiceTest 12 用例全过。已打包重启（PID 33012）。

#### 8.27 换装表达路由盲区："换一下"空承诺试穿（2026-08-06）

复验 8.26 时发现：用户「好,我要是换一下」，路由只挂 `[core]`，模型根据上下文（刚推荐完 043）
理解成试穿，回复"已经提交试穿任务了"却**无试穿工具可调**（virtual_try_on_reference_outfit 在 tryon 组，
不在 core）→ 空承诺，且 maybeAutoTryOn 因"换一下"不在 TRY_ON_INTENT 未兜底。
- **根因**：`TRY_ON_INTENT` 只覆盖"试穿/试试/穿一下"等，未覆盖"换上/换一下/换这身"等换装表达。
- **修复**：`TRY_ON_INTENT` 追加 `换上|换一下|换这身|换这一身`——模型按上下文把这类表达理解成试穿时，
  路由能挂 tryon 组提供工具；"换一套/再换一套"（换推荐，fashion_consultant 在 core 组）不受影响，
  仍走推荐。
- 测试补 2 个："换一下/换上这套试试/把这身换上"命中试穿意图；"再换一套"不命中试穿组 +
  "换一套试试看"（含"试试"）挂试穿组合理。AutoConsultTest 20 用例全过。已打包重启（PID 31364）。

#### 8.28 照片入库 + 试穿复验：两个盲区修复（2026-08-06）

复验照片入库与试穿链路（17:45-17:49，白色T恤入库 wardrobeItem=7 全通），发现两个盲区：
- **问题 1（"只存"入库盲区）**：用户「只存白色t恤」，路由只挂 `[core]` → 模型回复"没有衣橱入库的工具权限"。
  根因：`INTENT_WARDROBE_INTAKE` 只有"只要"，"只**存**/只留/只加入"未命中。
  修复：正则补 `只存|只留|只加入|只入|只保留`。
- **问题 2（衣橱单品试穿漏调空承诺）**：用户「试穿这件白色T恤」，模型调了 search_wardrobe 查到
  wardrobeItemId=7 后**未调 virtual_try_on_wardrobe_item**，回复"已经提交试穿任务了"空承诺。
  根因：`maybeAutoTryOn` 把"已调过任一穿搭域工具（含 search_wardrobe）"当作已试穿而短路；
  且衣橱单品试穿（wardrobe_item）本就没有漏调兜底。
  修复：
  - 短路条件收窄为"已调试穿工具或本轮在换推荐（fashion_consultant）"——仅查询衣橱不算已试穿；
  - 新增 `maybeAutoWardrobeItemTryOn`：hasWardrobeItemIntent 时按描述剥离动作词
    （"试穿这件白色T恤"→"白色T恤"）反射调 search_wardrobe_semantic 定位 wardrobeItemId，
    再补调 virtual_try_on_wardrobe_item，把后台任务提示追加到回复。
- 测试补 2 个：`onlySaveExpressionRoutesToIntake`（只存/只留命中入库组与兜底）、
  `autoTryOnFillsWardrobeItemWhenModelOnlySearched`（模型只查询时兜底补调 wardrobe_item 并 verify 调用）。
  AutoConsultTest 22 用例全过。已打包重启（PID 28708）。

#### 8.29 用户画像查询路由 + 概括输出（2026-08-07）

用户问「我当前的用户画像是什么」时，日志显示路由只挂 `[core]`，模型**没调 get_fashion_profile**
（该工具在 wardrobe_view 组未挂载），只能凭历史聊天猜测画像（"杭州/衣橱6件…"），读不到真实偏好数据。
- **根因**：`INTENT_WARDROBE_VIEW` 无"画像/偏好"表达，画像查询意图无路由。
- **修复**：
  - 新增 `INTENT_FASHION_PROFILE` 正则（我的画像/用户画像/我的偏好/我的风格/我的预算/我的穿衣风格/
    我适合什么风格/了解我等），`toolsForPrompt` 命中即挂 wardrobe_view 组（含 get_fashion_profile）；
  - `get_fashion_profile` 工具描述补充"调用后用 2-3 句自然语言概括画像，不罗列字段名"；
  - `describeProfile` 改输出概括句优先（"当前穿搭画像概括：偏简约休闲风格，常出现在通勤场合，
    预算约 500-1500 元…"），模型可直接转述。
- 测试补 1 个：`profileQueryRoutesToWardrobeViewForProfileTool`（5 种画像问法命中 wardrobe_view 组，
  天气闲聊不误挂）。AutoConsultTest 23 用例全过。已打包重启（PID 27088）。
