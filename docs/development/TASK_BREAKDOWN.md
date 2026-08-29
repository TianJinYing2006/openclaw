# 项目分工方案 —— 代码合并统一 + 穿搭 Agent 系统搭建

> **日期**: 2026-07-30  
> **团队**: 6 人  
> **当前分支**: `feature/rag`  
> **截止日期**: 2026-08-11

---

## 第一部分：当前状态评估

### 已完成的工作

| 工作项 | 状态 | 说明 |
|--------|------|------|
| 六分支集成 | ✅ 已完成 | `codex/ilink-integration` 已合并 `wangentong`(基线)、`tjy`、`zxm`、`ilink-lwb`、`wgy`、`zx` |
| iLink 登录/长轮询 | ✅ 已验证 | Session 持久化 + Cursor 游标管理 |
| AI 双协议路由 | ✅ 已验证 | 纯文本走 Spring AI Completion，多模态走 Responses |
| 媒体处理 | ✅ 已验证 | 图片/文件/视频/语音 全链路跑通 |
| SQLite 持久化 | ✅ 已实现 | `storage/db/` 包已提交，替代 Caffeine |
| 工具系统 (Tool) | ✅ 已实现 | `@Tool` 注册机制 + 已有 20+ 工具 |
| Agent 编排骨架 | ✅ 已实现 | `orchestration/` 包提供 Coordinator/Context/SessionContext |
| 业务服务 | ✅ 已实现 | 天气/导航/日程/搜索/快递/汇率/天行数据 |

### 未跟踪的新增内容

| 内容 | 所属阶段 | 处理建议 |
|------|---------|---------|
| `ai/fashion/` 包 | 穿搭 Agent | 当前只有空 `AgentPrompts.java`，需作为 Phase 2 起点 |
| `docs/fashion-agent-design.md` | 穿搭 Agent | 设计文档，纳入版本管理 |
| `docs/fashion-business-logic.md` | 穿搭 Agent | 业务逻辑文档，纳入版本管理 |
| `data/xiaohongshu_fashion_seed.json` | 穿搭 Agent | 种子数据，先纳入 `.gitignore` 或管理存储 |
| `fashion_crawl/` | 穿搭 Agent | 爬虫数据/工具，需评估是否纳入版本管理 |
| `.db/` | 构建产物 | 已 `.gitignore`，无需跟踪 |
| `SQLITE_STORAGE_PLAN.md` | 架构文档 | 已实现，纳入 `docs/architecture/` |

---

## 第二部分：第一阶段 — 代码合并统一 (Day 0–2)

### 目标
确保代码库结构一致、测试通过、构建干净，为穿搭 Agent 开发提供稳定基线。

### 团队分工

#### 👤 成员 A & D — 代码结构清理与基线验证
| 任务 | 内容 | 验收标准 |
|------|------|---------|
| 1.1 分支同步 | 将 `feature/rag` 落后于 `origin/master` 的部分 rebase 或合并 | `git log --oneline` 无杂乱分叉 |
| 1.2 未跟踪文件整理 | 分类处理：文档纳入版本、产物加入 `.gitignore` | `git status` 干净 |
| 1.3 包结构审计 | 确认 `com.example.ykdsummer` 下包名统一、无同名冲突类 | `mvn compile` 零错误 |
| 1.4 设计文档入库 | 将 `fashion-agent-design.md`、`fashion-business-logic.md`、`SQLITE_STORAGE_PLAN.md` 纳入 `docs/` | 路径规范，README 可导航 |

#### 👤 成员 B & C — 测试与构建验证
| 任务 | 内容 | 验收标准 |
|------|------|---------|
| 1.5 全量测试 | 运行 `mvn test`，确认原有 94 项测试全部通过 | `mvn test` 全部通过或仅 Live 探针跳过 |
| 1.6 编译验证 | `mvn package -DskipTests` 构建成功 | 输出 `target/ykd-summer-*.jar` |
| 1.7 Maven Enforcer 检查 | 验证 JDK 21 + Maven 3.9.x 约束生效 | 构建开始阶段正确触发 |

#### 👤 成员 E & F — 基础设施与配置
| 任务 | 内容 | 验收标准 |
|------|------|---------|
| 1.8 环境变量检查 | 整理 `.env.example` 或配置说明，列出所需 API Key | 文档可指导新成员 5 分钟完成配置 |
| 1.9 Docker 环境确认 | 检查 RAGFlow Docker Compose 配置（`docker/ragflow/`） | 可一键 `docker compose up` |
| 1.10 `.gitignore` 更新 | 确保 `.db/`、`fashion_crawl/` 产物、本地日志已被忽略 | 无敏感文件被跟踪 |

### 里程碑 M0 — 基线就绪 (Day 2 结束)
- ✅ `mvn test` 全部通过
- ✅ `git status` 干净，无阻塞冲突
- ✅ 设计文档全部入库
- ✅ 穿搭 Agent 开发环境可用

---

## 第三部分：第二阶段 — 穿搭 Agent 系统搭建 (Day 1–14)

基于 `docs/fashion-agent-design.md` 和 `docs/fashion-business-logic.md` 的完整方案。

### 整体架构

```
用户微信 → FashionAgentService (@Tool 路由)
  → AgentCoordinator (核心编排器)
    → QueryAnalyzer (查询分解)
    → RAGFlow (知识检索)
    → Stylist Agent (创意生成 3 套方案)
    → Critic Agent (并行评审)
    → Trend Agent (并行趋势验证)
    → Coordinator Agent (冲突裁决)
    → FashionRuleEngine (规则校验)
    → PreferenceLearner (偏好学习)
    → FashionMemoryService (跨会话记忆)
  → 格式化输出 → 微信回复
```

### 详细分工

#### 👤 成员 A — RAGFlow + 检索 + 查询分析
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2A.1 RAGFlow Docker 部署 | 编写 `docker/ragflow/docker-compose.yml` + 首次启动文档 | 30 行 YAML | 确认 Docker 环境 |
| 2A.2 种子数据准备 | 清洗 `data/xiaohongshu_fashion_seed.json`，导入 RAGFlow 知识库 | 50 行脚本 | 2A.1 |
| 2A.3 RagflowRetriever | 封装 RAGFlow HTTP API，支持混合检索 + Rerank | 120 行 | 2A.1 |
| 2A.4 QueryAnalyzer | LLM 调用，将用户模糊需求→结构化 `QueryParams` | 100 行 | 2A.3 |
| 2A.5 MCTS (可选) | 搭配方案搜索，限制 20 轮迭代、深度 4 | 150 行 | 独立模块 |

**JSON Schema 对齐**: QueryAnalyzer 输出 `{decomposedQueries: [], params: {scene, season, formality, gender}}`

#### 👤 成员 B — Stylist + Critic Agent
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2B.1 AgentPrompts 完善 | 编写 Stylist 和 Critic 的 system prompt + 输出 JSON Schema | 80 行 | 设计文档 |
| 2B.2 StylistAgent | 创意生成 3 套穿搭方案，含搭配理由 | 150 行 | 2B.1 + RAGContext |
| 2B.3 CriticAgent | 并行评审，指出每套方案的潜在问题 | 120 行 | 2B.1 |
| 2B.4 单 Agent 单元测试 | Mock LLM 调用验证 JSON 输出格式 | 100 行 | 2B.2 + 2B.3 |

**输出格式**:
- Stylist → `OutfitSuggestion[]{top, bottom, shoes, accessories, reason, colorScheme, tags}`
- Critic → `CriticReview[]{suggestionIndex, issues[], severity, suggestions[]}`

#### 👤 成员 C — Trend + Coordinator Agent
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2C.1 TrendAgent | 调用 Web Search 验证当前流行趋势，或基于 LLM 自身知识 | 100 行 | `WebSearchService` |
| 2C.2 CoordinatorAgent | 冲突裁决 → 精炼最终 2–3 套方案 + 最终理由 | 150 行 | 2B + 2C.1 |
| 2C.3 并行编排 | 使用 `CompletableFuture` 实现 Critic+Trend 真并行 | 50 行 | AgentCoordinator |
| 2C.4 单 Agent 测试 | 验证 Coordinator 正确裁决冲突场景 | 100 行 | 2C.2 |

**裁决优先级**: 体型适配 > 场合适配 > 风格偏好 > 趋势热度

#### 👤 成员 D — AgentCoordinator + Memory + 框架
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2D.1 数据模型 | 创建 `fashion/model/` 下所有 VO/Entity（QueryParams, RAGContext, OutfitSuggestion, FashionResult...） | 100 行 | 多人对齐 |
| 2D.2 AgentCoordinator 增强 | 在现有 `orchestration/` 基础上实现 5 步编排链 + 超时控制(每步 10s) | 200 行 | `ToolRegistry` |
| 2D.3 并行 fork/join | `CompletableFuture` 并行执行 Critic+Trend，超时降级 | 60 行 | 2D.2 |
| 2D.4 FashionMemoryService | SQLite 存储：用户档案/历史摘要/偏好向量，跨会话加载 | 150 行 | `SqliteChatMemory` |
| 2D.5 AiTraceLogger 集成 | 在编排关键节点埋点，记录每步延迟、token 消耗 | 50 行 | 已有 `AiTraceLogger` |
| 2D.6 接口对齐 Day1 | 每天同步接口定义，组织 Day7 强制集成 | — | 全体 |

#### 👤 成员 E — 规则引擎 + 偏好学习 + 安全兜底
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2E.1 规则表设计 | SQLite 表：硬规则(体型+场景)、软规则(色彩+风格)、安全兜底表 | 80 行 SQL | — |
| 2E.2 FashionRuleEngine | 加载规则 → 预注入 Stylist prompt + 后校验 Coordinator 输出 | 150 行 | 2E.1 |
| 2E.3 PreferenceLearner | Bradley-Terry 在线偏好学习（用户选择→更新偏好权重） | 120 行 | 2D.4 |
| 2E.4 FeatureExtractor | 从用户描述中提取体型/风格/色彩偏好特征 | 80 行 | — |
| 2E.5 安全兜底 | SQLite 表存通用穿搭建议，LLM 全挂时直接返回 | 60 行 | — |

**降级链**: RAGFlow 挂→跳规则引擎+LLM → Coordinator 输出校验失败→规则引擎修正 → LLM 全挂→安全兜底表

#### 👤 成员 F — 数据工程 + VTON + 格式化 + 联调
| 任务 | 内容 | 代码量 | 依赖 |
|------|------|--------|------|
| 2F.1 种子数据标注 | 扩充 `fashion_crawl/` 数据，标注 100+ 条高质量穿搭参考 | 标注工作 | — |
| 2F.2 输出格式化 | 将 `FashionResult` 渲染为微信友好文本格式（方案展示+理由+建议） | 100 行 | 2D.1 |
| 2F.3 VTON 集成 (可选) | 接入试穿效果图生成，若不可用则纯文字兜底 | 150 行 | — |
| 2F.4 @Tool 入口 | 在 `ai/tool/` 下创建 `FashionAgentService`，通过 `@Tool` 暴露给微信 | 60 行 | 2D.2 |
| 2F.5 端到端测试 | 编写 3–5 条用户故事全链路测试（Mock + 真实 LLM） | 150 行 | 全体 |
| 2F.6 演示准备 | 录屏 + 演示用例 + README 更新 | 文档 | 全体 |

---

## 第四部分：时间线

### Day 1–2 (Phase 1: 合并统一)
```
全体：完成代码基线清理
A+D: 分支同步 + 结构清理 + 文档入库
B+C: 测试验证 + 构建确认
E+F: 环境配置 + Docker 确认
```

### Day 1–4 (Phase 2: 基建 + 独立开发)
```
Day 1: D 创建数据模型 + 全体对齐接口 + D/E 建表 + A/F 标注数据 + A 部署 RAGFlow Docker
Day 2–3: A RagflowRetriever + QueryAnalyzer
         B AgentPrompts 初版 + Stylist baseline
         D AgentCoordinator 骨架
         E 规则引擎 + 安全兜底表
Day 4–5: B Stylist + Critic 完整实现
         C Trend + Coordinator 完整实现
         D AgentCoordinator + 超时控制 + AiTraceLogger
   验收: 各模块可独立测试，输出符合 JSON Schema
```

### Day 5–7 (管道打通)
```
B+C: 单 Agent 调用验证
D: AgentCoordinator 串联测试（Critic+Trend 真并行）
A+D: RAGFlow 集成到 Agent 管道
E: 规则引擎前后校验集成
F: @Tool 入口 + 格式化
```
**Day 7 里程碑** — AgentCoordinator 完整管道跑通：
```
微信 → QueryAnalyzer → RAGFlow → Stylist → Critic+Trend(并行) → Coordinator → 规则校验 → 安全兜底 → 格式化 → 回复
```
**全体验收**: 全管道集成测试通过

### Day 8–10 (偏好学习 + 记忆)
```
E: PreferenceLearner + FeatureExtractor 实现
D: FashionMemoryService（加载/归档/摘要）
D+E: 偏好学习集成到 Coordinator 后处理
```

### Day 11–14 (集成 + 打磨 + 演示)
```
F: VTON 集成 + 格式化完善
A: MCTS 搜索引擎（时间充裕则加）
全队: 异常处理 / Prompt 调优 / 规则扩充 / 边界测试
Day 14: 演示准备（3–5 条用例 + 录屏 + README）
```

---

## 第五部分：接口契约 (各成员对齐)

### 关键数据流转格式

```
用户输入 (String)
  → QueryAnalyzer → QueryParams {
      decomposedQueries: String[],
      params: { scene, season, formality, gender, bodyType, stylePreference }
    }
  → RAGFlow → RAGContext {
      rules: FashionRule[],
      references: ReferenceLook[],
      params: QueryParams
    }
  → StylistAgent → OutfitSuggestion[3]
  → CriticAgent → CriticReview[3]  (并行)
  → TrendAgent → TrendVerification (并行)
  → CoordinatorAgent → OutfitSuggestion[2–3]
  → FashionRuleEngine → ValidationResult
  → PreferenceLearner → (更新权重)
  → FashionResult {
      primaryOption: OutfitSuggestion,
      alternativeOptions: OutfitSuggestion[],
      weatherAdvice: String,
      reasoning: String
    }
  → 格式化 → 微信消息文本
```

### 降级策略矩阵

| 故障场景 | 行为 | 责任人 |
|---------|------|--------|
| RAGFlow 不可用 | 跳过知识层，规则+LLM 兜底 | A |
| Stylist/Critic/Trend 超时 | 返回已完成的 Agent 结果，跳过失败项 | D |
| Coordinator 裁决失效 | 使用 Stylist 原始结果 | D |
| 规则校验不符 | 尝试修正，修正失败则返回 Stylist 最接近项 | E |
| 所有 LLM 调用失败 | 安全兜底表输出通用穿搭建议 | E |
| VTON 不可用 | 纯文字输出 | F |

---

## 第六部分：风险与依赖

| # | 风险 | 概率 | 影响 | 应对 |
|---|------|------|------|------|
| 1 | 六分支遗留冲突在测试中暴露 | 中 | 延误 Phase 1 | Day 1–2 全量 `mvn test` 早暴露 |
| 2 | LLM 调用超时 (管道总延迟 >30s) | 中 | 用户体验差 | 每步 10s 超时，Critic+Trend 真并行，先回文字再补图片 |
| 3 | Agent JSON 解析失败 | 高 | 管道中断 | Jackson lenient + 重试一次 + 默认值替代 |
| 4 | RAGFlow 检索质量差 | 高 | 知识增强不明显 | Day 1 开始标数据；规则优先 + Prompt 工程补偿 |
| 5 | 接口进度不同步导致集成返工 | 中 | 延误集成 | D 每天同步接口 + Day 5–7 强制集成窗口 |
| 6 | VTON 不可用 | 中 | 缺视觉展示 | 纯文字方案降级，不影响核心逻辑 |

---

## 第七部分：验收标准总表

### Phase 1 验收 (Day 2)
- [ ] `mvn test` 零失败（除 Live 探针）
- [ ] `mvn package` 构建通过
- [ ] `git status` 干净整洁
- [ ] 设计文档入库，README 可导航

### Phase 2 里程碑 Day 7 验收
- [ ] RAGFlow API 可调用并返回有效结果
- [ ] QueryAnalyzer 输出正确 JSON
- [ ] Stylist + Critic 独立可运行
- [ ] Trend + Coordinator 独立可运行
- [ ] AgentCoordinator 完整管道跑通
- [ ] 规则引擎前后校验正常
- [ ] @Tool 入口可触发，微信可收到格式化回复

### Day 14 终验
- [ ] 偏好学习器可记录并影响推荐
- [ ] 跨会话记忆正常（重启再问能记住偏好）
- [ ] 安全兜底在 LLM 全挂时仍能回复
- [ ] 3–5 条完整用户故事演示可用
- [ ] README 更新至最新
