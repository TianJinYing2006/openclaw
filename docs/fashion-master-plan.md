# 穿搭 Agent — 统筹方案与分工

- **日期**: 2026-07-31
- **Demo 截止**: 周六 (2026-08-02)
- **团队**: 6人 (A/B/C/D/E/F)

---

## 一、全局进度总览

### 已完成

| 模块 | 状态 | 说明 |
|------|------|------|
| 模型层 (9个record类) | ✅ | FashionRequest/Result/AnalyzedQuery/SeedEntry/RetrievedChunk/StylistOutput/CriticOutput/TrendOutput/CoordinatorOutput |
| AgentLlmCaller | ✅ | ChatModel封装 + JSON三层解析 + 超时 + 虚拟线程 |
| AgentPrompts | ✅ | 5个Agent系统提示词 |
| 4个Agent | ✅ | Stylist/Critic/Trend/Coordinator，可运行 |
| AgentCoordinator | ✅ | 编排+并行+降级+timing日志+mock测试数据 |
| QueryAnalyzer | ✅ | LLM分析+关键词兜底 |
| FashionKnowledgeService | ✅ | FTS5检索+BM25加权+双路召回+标签映射 |
| FashionSchemaInitializer | ✅ | trigram tokenizer + 100条种子加载 |
| FashionTagMapper | ✅ | LLM输出↔种子数据枚举映射 |
| FashionAgentService | ✅ | @Tool入口 |
| FashionResponseFormatter | ✅ | JSON→微信文案 |
| 编译验证 | ✅ | 零错误 |

### 待完成

分三大块：**基础管道调优**、**真实数据导入**、**三个扩展功能**。

---

## 二、三大工作块

### 块1：基础管道调优（P0 — demo必须跑通）

目标：用户发"今天去海边穿什么" → 收到结构化穿搭推荐文案。

| 任务 | 负责人 | 依赖 | 估时 |
|------|--------|------|------|
| Stylist Prompt 调优：确保3套差异化方案 | B | 无 | 0.5天 |
| Critic Prompt 调优：确保维度完整+指出不足 | B | 无 | 0.5天 |
| Trend Prompt 调优：确保趋势分有区分度 | C | 无 | 0.5天 |
| Coordinator Prompt 调优：确保裁决优先级正确 | C | 无 | 0.5天 |
| QueryAnalyzer Prompt 调优：场景/季节提取准确 | A | 无 | 0.5天 |
| FTS5 查询质量验证：不同场景检索相关性 | A | 无 | 0.5天 |
| 端到端联调 + 超时参数调整 | D | A/B/C完成 | 0.5天 |
| @Tool 路由验证：穿搭类请求正确触发 | F | D联调 | 0.5天 |
| 输出文案优化 + 演示用例准备 | F | 管道跑通 | 0.5天 |

### 块2：真实数据导入（P0 — demo质量保障）

目标：种子数据从100条扩到200+条，补充规则和兜底方案。

| 任务 | 负责人 | 依赖 | 估时 |
|------|--------|------|------|
| 用 mcp 工具爬取50条帖子详情(标题/正文/图片) | A | 登录态 | 0.5天 |
| 下载帖子图片到 fashion_images/ | B | A爬取 | 0.5天 |
| AI标注脚本：图片识别→outfit + LLM→tags | F | A爬取 | 1天 |
| 数据清洗+去重+合并到种子JSON | A+F | F标注 | 0.5天 |
| 穿搭规则数据(20条) + 安全兜底方案(5条) | E | 无 | 0.5天 |
| FashionSchemaInitializer 支持加载规则/兜底 | D | E数据 | 0.5天 |
| FashionRuleEngine 实现(前置注入+后置校验) | E | D建表 | 1天 |
| SafetyFallbackService 实现 | E | D建表 | 0.5天 |

### 块3：三个扩展功能（P1 — demo后迭代）

目标：用户画像、本地衣柜、AI换衣。

| 任务 | 负责人 | 依赖 | 估时 |
|------|--------|------|------|
| **用户画像** UserProfile + FashionMemoryService + ProfileExtractor | D | 无 | 1.5天 |
| **用户画像** 管道集成：注入Stylist/Coordinator prompt | B/C | D完成 | 0.5天 |
| **本地衣柜** WardrobeItem + WardrobeService + WardrobeMatcher | A | 无 | 2天 |
| **本地衣柜** @Tool方法：用户微信对话管理衣柜 | A | 服务层 | 0.5天 |
| **本地衣柜** 管道集成：推荐时匹配已有单品 | B/C | A完成 | 0.5天 |
| **AI换衣** VtonPromptBuilder + VtonService | F | 无 | 1天 |
| **AI换衣** 管道集成：Coordinator后生成试穿图 | F | 管道跑通 | 0.5天 |
| **偏好学习** PreferenceLearner + FeatureExtractor (可选) | E | 画像完成 | 1.5天 |

---

## 三、时间线（4天，到周六demo）

### Day 1 (周四) — 全员并行启动

```
A: 爬取50条帖子详情 (0.5天) → 开始FTS5查询质量验证 (0.5天)
B: Stylist Prompt调优 (0.5天) → Critic Prompt调优 (0.5天) → 下载帖子图片 (穿插)
C: Trend Prompt调优 (0.5天) → Coordinator Prompt调优 (0.5天)
D: 维护框架+解决集成问题 → 开始FashionSchemaInitializer扩展
E: 穿搭规则数据+兜底方案录入 (0.5天) → 开始FashionRuleEngine实现
F: AI标注脚本开发 (1天)
```

**Day 1 结束验收**：
- A: 50条原始帖子已爬取，FTS5检索质量验证通过
- B: Stylist输出3套差异化方案，Critic每套有不足指出
- C: Trend趋势分有区分度，Coordinator裁决优先级正确
- D: SchemaInitializer支持规则/兜底表
- E: 20条规则 + 5条兜底方案数据就绪
- F: 标注脚本可运行，产出标注JSON

### Day 2 (周五) — 数据导入 + 管道联调

```
A+F: 数据清洗+合并到种子JSON (0.5天) → A做衣柜开发
B: 衣柜管道集成准备 → 画像管道集成准备
C: 衣柜管道集成准备 → 画像管道集成准备
D: 端到端联调+超时调整 (0.5天) → SchemaInitializer更新
E: FashionRuleEngine完成 (0.5天) → SafetyFallbackService (0.5天)
F: 标注脚本产出200+条数据 → 开始VtonService
```

**Day 2 结束验收**：
- 种子数据200+条已加载到FTS5
- 规则引擎+兜底方案集成到管道
- 完整管道端到端跑通（含规则校验）
- @Tool路由验证通过

### Day 3 (周六上午) — 演示打磨

```
全队: 演示用例测试 + Prompt微调 + 录屏
F: 输出文案优化 + 演示视频
D: 性能检查（管道<30s）+ 降级链验证
```

**Demo 验收用例**：
1. "今天去海边穿什么" → 海边场景推荐
2. "下周参加婚礼" → 婚礼场景推荐（规则校验：不推荐纯白）
3. "上班通勤穿搭" → 通勤场景推荐
4. LLM超时 → 降级到Stylist首选或安全兜底

### Day 3 (周六下午) — Demo

---

## 四、依赖关系图

```
                    ┌─────────────────────────────────────────┐
                    │           基础管道调优 (P0)               │
                    │  A: QueryAnalyzer+FTS5                   │
                    │  B: Stylist+Critic Prompt                │
                    │  C: Trend+Coordinator Prompt             │
                    │  D: 联调+超时    F: 路由+文案+演示        │
                    └──────────────────┬──────────────────────┘
                                       │
                    ┌──────────────────┼──────────────────────┐
                    │                  │                      │
              ┌─────▼─────┐    ┌───────▼───────┐    ┌────────▼────────┐
              │ 真实数据   │    │  扩展功能      │    │   扩展功能       │
              │ 导入 (P0)  │    │               │    │                 │
              │           │    │ 用户画像 (D)   │    │ 本地衣柜 (A)    │
              │ A: 爬取   │    │       ↓       │    │       ↓         │
              │ F: 标注   │    │ 管道集成(B/C)  │    │ 管道集成(B/C)   │
              │ E: 规则   │    └───────────────┘    └─────────────────┘
              │ D: 加载   │                              ↓
              │ E: 引擎   │                    ┌────────────────┐
              └───────────┘                    │  AI换衣 (F)    │
                                               │  VtonService   │
                                               └────────────────┘
```

**关键依赖路径**：
1. 基础管道调优 → 端到端联调 → Demo（必须路径）
2. A爬取 → F标注 → 数据合并 → D加载（数据路径，与管道调优并行）
3. E规则数据 → D建表 → E引擎实现 → 管道集成（规则路径）
4. 用户画像 → 管道集成（扩展路径，demo后）

---

## 五、每人任务卡

### A — 数据采集 + RAG + 衣柜

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | 爬取50条帖子详情 | `fashion_crawl/raw_posts/*.json` |
| P0 | FTS5查询质量验证 | 验证报告 |
| P0 | 数据清洗+合并种子JSON | 更新 `xiaohongshu_fashion_seed.json` |
| P1 | 本地衣柜 WardrobeService | `fashion/wardrobe/` 三个类 |
| P1 | 衣柜 @Tool 方法 | 微信对话管理衣柜 |

### B — Stylist + Critic + 管道集成

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | Stylist Prompt调优 | 3套差异化方案 |
| P0 | Critic Prompt调优 | 完整评审维度 |
| P0 | 下载帖子图片 | `fashion_images/*.png` |
| P1 | 画像管道集成 | StylistAgent增加UserProfile参数 |
| P1 | 衣柜管道集成 | 推荐时匹配已有单品 |

### C — Trend + Coordinator + 管道集成

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | Trend Prompt调优 | 趋势分有区分度 |
| P0 | Coordinator Prompt调优 | 裁决优先级正确 |
| P1 | 画像管道集成 | CoordinatorAgent增加UserProfile参数 |
| P1 | 衣柜管道集成 | 推荐时标注已有单品 |

### D — 框架维护 + 联调 + 用户画像

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | 端到端联调+超时调整 | 管道跑通 |
| P0 | SchemaInitializer扩展 | 规则/兜底表加载 |
| P0 | 性能检查+降级链验证 | 管道<30s |
| P1 | 用户画像 | `fashion/memory/` 三个类 |
| P1 | 画像管道集成 | AgentCoordinator加载画像 |

### E — 规则引擎 + 兜底 + 偏好学习

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | 穿搭规则数据(20条) | `data/fashion_rules.json` |
| P0 | 安全兜底方案(5条) | `data/safety_fallbacks.json` |
| P0 | FashionRuleEngine | `fashion/knowledge/FashionRuleEngine.java` |
| P0 | SafetyFallbackService | `fashion/knowledge/SafetyFallbackService.java` |
| P2 | 偏好学习器 | `fashion/learning/` 两个类 |

### F — 标注脚本 + 换衣 + 演示

| 优先级 | 任务 | 产出 |
|--------|------|------|
| P0 | AI标注脚本 | `fashion_crawl/label.py` |
| P0 | 数据清洗+合并 | 200+条种子数据 |
| P0 | @Tool路由验证 | 穿搭请求正确触发 |
| P0 | 输出文案优化+演示用例 | 演示视频 |
| P1 | AI换衣 VtonService | `fashion/integration/` 两个类 |

---

## 六、验收标准

### P0 Demo 验收（周六）

| 验收项 | 标准 |
|--------|------|
| 基本流程 | 微信发"去海边穿什么" → 收到穿搭推荐 |
| 多Agent | Stylist生成3套 → Critic评审 → Trend分析 → Coordinator裁决 |
| RAG | FTS5检索返回相关博主穿搭，注入Agent prompt |
| 数据量 | 种子数据200+条，覆盖5+场景 |
| 规则校验 | 婚礼场景不推荐纯白 |
| 降级链 | LLM超时 → 降级到Stylist首选或安全兜底 |
| 响应时间 | 管道总耗时 < 30s |
| 演示用例 | 3条场景用例 + 录屏 |

### P1 扩展功能验收（demo后）

| 验收项 | 标准 |
|--------|------|
| 用户画像 | 记住用户体型/风格偏好，下次推荐生效 |
| 本地衣柜 | 用户上传衣服照片 → 识别存入 → 推荐时匹配 |
| AI换衣 | 推荐后生成穿搭效果图发送到微信 |

---

## 七、风险与应对

| 风险 | 概率 | 应对 |
|------|------|------|
| 小红书爬取被限流 | 中 | A优先爬取，备选用已有100条mock数据演示 |
| AI标注质量差 | 中 | F标注后A人工抽检10%，不合格的丢弃 |
| LLM调用超时 | 中 | D调整超时参数，确保降级链生效 |
| 管道总耗时>30s | 中 | D监控timing日志，Critic/Trend并行已实现 |
| Prompt调不优 | 低 | B/C用mock测试数据验证，不依赖LLM |
| 团队成员进度不同步 | 中 | D每天同步，Day2强制集成窗口 |
