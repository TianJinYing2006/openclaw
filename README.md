# 微信 AI 穿搭助手（WeChat AI Fashion Assistant）

基于 **Spring Boot 3.5 + Spring AI 1.1.8 + iLink 微信协议**的对话式 AI 穿搭助手。用户在微信里发一句"帮我搭配一套面试装"，机器人便会通过一条完整的 **Multi-Agent 管道**完成：需求分析 → 混合检索 → 方案生成 → 并行评审 → 综合裁决，并把图文搭配结果异步回传到微信。

> 这不是一个"调 API 的 demo"，而是一个把 **Multi-Agent 协作、RAG 混合检索、MCP 工具编排、工程化兜底**揉进真实微信产品形态里的完整实现。所有关键数字均来自真实链路实测。

---

## 为什么值得看（3 句话）

1. **真正的 Multi-Agent 生产实现**：不是"一个 prompt 假装多角色"，而是 5 个独立 Agent（QueryAnalyzer / Stylist / Critic ∥ Trend / Coordinator）通过结构化 JSON 协议协作，每个环节都有容错与降级阶梯。
2. **深度和广度同时在线**：RAG 混合检索、MCP 统一工具编排（40 个工具、`@AgentTool` 白名单机制）、Flyway 版本化迁移、异步任务 / 超时 / 限流 / 去重 / 会话隔离全套工程化。
3. **有真实数据背书**：Critic 与 Trend 并行评审使评审阶段时延从约 12.3s 降到约 8.8s（真实链路实测，约 -28%）；向量检索异常时自动降级，降级后未出现因检索模块导致的请求失败。

---

## 核心数字速览

| 指标 | 数值 |
| --- | --- |
| Agent 角色 | 5（QueryAnalyzer / Stylist / Critic ∥ Trend / Coordinator） |
| 注册工具 | 40（`@AgentTool` 白名单自动注册） |
| RAG 检索路 | 3（RAGFlow 向量 + MySQL FULLTEXT 关键词 + Qdrant 衣橱语义） |
| 数据库迁移 | Flyway 版本化，迭代至 V24 |
| 并行评审 | 评审阶段 median 约 8s，较串行降低约 28%（实测 12.3s → 8.8s，真实链路） |
| 检索降级 | 异常自动降级，降级后零请求失败 |
| 演示 | 纯前端可交互 Demo（见下文） |

---

## 功能全景

| 能力域 | 说明 |
| --- | --- |
| **穿搭推荐** | 多 Agent 管道（QueryAnalyzer → RAG → Stylist → Critic ∥ Trend → Coordinator），生成搭配方案并附带参考图 |
| **个人衣橱** | 发送服装照片自动抠图入库，支持语义搜索（Qdrant）和关键词检索 |
| **虚拟试衣** | 用户上传全身照作为人物模板，选择衣橱单品生成上身效果 |
| **图片生成/编辑** | 文生图、图生图、图片改版、视觉识别，后台异步生成完成后自动回传 |
| **语音/视频** | 语音转文字（腾讯云 ASR）、文字转语音（阿里云 TTS）、视频抽帧分析 |
| **定时提醒** | 微信定时 Agent 任务，到点自动触发 AI 生成提醒内容 |
| **联网搜索** | 博查搜索或 MCP 外部搜索服务 |
| **多实例管理** | 通过本地管理站管理多个 iLink 微信实例 |
| **用户画像** | 反馈 → LLM 抽取 → 偏好写入，衣橱排序按偏好加权 |

---

## 架构总览

```
微信消息
  │
  ├─ [接入层]  ILinkBotService            — iLink SDK 长轮询 + 去重 + 限流 + 线程池
  │
  ├─ [路由层]  ILinkReplyService          — 消息类型分流（文件/命令/语音/视频/文字/图片）
  │
  ├─ [会话层]  AiChatService              — Caffeine 多轮记忆 + 用量预算 + 顺序保证
  │
  ├─ [网关层]  RoutingLlmGateway          — 协议选择（Chat Completions / Vision / Responses）
  │              ├─ SpringAiChatCompletionsGateway — Spring AI Function Calling
  │              └─ ChatCompletionsVisionGateway   — 图片视觉通道（独立视觉模型）
  │                    └─ ToolRegistry 白名单（@AgentTool 注解，默认拒绝）
  │
  └─ [管道层]  fashion_consultant（FashionAgentService）
                  └─ AgentCoordinator 多 Agent 协作管道
```

### 穿搭多 Agent 管道

模型检测到穿搭需求时调用 `fashion_consultant`，进入 `AgentCoordinator` 多 Agent 管道：

```
Step 0: 用户画像上下文（向量相似度检索历史偏好）
Step 1: QueryAnalyzer 分析需求        → 失败则关键词兜底
Step 2: FashionKnowledgeService RAG   → 失败则空知识
Step 3: StylistAgent 生成方案（串行） → 失败则安全兜底
Step 4: CriticAgent ∥ TrendAgent 并行评审 → 各自失败互不影响
Step 5: CoordinatorAgent 综合裁决     → 失败则降级 Stylist 首选
```

简单请求（如"推荐一套日常穿搭"）在 Step 3 后直接返回，跳过 Critic/Trend/Coordinator 以降低延迟。生成方案后异步发送参考图（overview + top + bottom 单品图），通过 `referenceOutfitId` 保证图文对齐。

### 检索体系（三路混合）

| 检索方式 | 用途 | 数据源 |
| --- | --- | --- |
| RAGFlow | 穿搭知识库向量检索 | `data/fashion_docs/` 161 篇搭配文档 |
| MySQL FULLTEXT | 关键词检索（默认降级路径） | `fashion_seed_fts` 表 |
| Qdrant | 用户衣橱语义搜索 | 个人衣橱单品向量 |

检索效果按真实用户查询日志做了可复现基准评测与多轮 A/B 实验（rerank / query rewrite / RRF 融合 / HyDE），结论沉淀在 `docs/` 与 `scripts/` 中。

### MCP Provider 切换

以下功能均支持通过 `@ConditionalOnProperty` 在本地实现和外部 MCP Server 之间切换，默认使用本地实现：

| 功能 | 配置项 | 默认值 |
| --- | --- | --- |
| 网页搜索 | `app.web-search.provider` | `bocha` |
| 服装抠图 | `app.fashion.cutout.provider` | `reference-image` |
| 衣橱照片分析 | `app.fashion.analysis.provider` | `chat-completions` |
| 虚拟试衣 | `app.fashion.tryon.provider` | `reference-image` |
| 天气查询 | `app.weather.provider` | `uapis` |

---

## 核心设计亮点

### 1. 多 Agent 协作管道（含全链路降级阶梯）

AgentCoordinator 编排 5 个 Agent 角色，每一层失败都有明确的兜底策略（关键词兜底 → 空知识 → 安全回复 → Stylist 首选），保证最坏情况下用户也能得到回应。

- [AgentCoordinator](src/main/java/com/wechatbot/fashion/ai/fashion/look/agent/AgentCoordinator.java)
- [StylistAgent / CriticAgent / TrendAgent / CoordinatorAgent](src/main/java/com/wechatbot/fashion/ai/fashion/look/agent/)
- [设计文档](docs/AGENT_ORCHESTRATION.md) / [穿搭 Agent 设计](docs/fashion-agent-design.md)

### 2. 并行评审与结构化 JSON 协议

Critic（质量评审）与 Trend（趋势判断）并行执行，通过固定 JSON 结构 + Prompt 约束（`AgentPrompts`）保障 Agent 间通信稳定。**真实链路实测：评审阶段串行约 12.3s → 并行约 8.8s（约 -28%），median 约 8s。**

- [Agent 输出模型（内心独白 + 最终结论）](src/main/java/com/wechatbot/fashion/ai/fashion/look/model/)

### 3. RAG 混合检索 + 可复现 A/B 评测

穿搭知识走 RAGFlow 向量检索、MySQL FULLTEXT 关键词降级，衣橱单品走 Qdrant 语义搜索，结果合并 + 上下文注入。检索效果按真实用户查询日志做了基准评测与多轮 A/B 实验（rerank / query rewrite），结论均附评测脚本与数据。

- [QueryAnalyzer](src/main/java/com/wechatbot/fashion/ai/fashion/look/rag/QueryAnalyzer.java)
- [RAG 优化完整记录](docs/rag-optimization-full-record.md) / [基线报告](docs/_archive/rag-report-20260818.md)
- [A/B 实验脚本](scripts/p2_rerank_ab.py) / [p3_query_rewrite_ab.py](scripts/p3_query_rewrite_ab.py)

### 4. MCP 统一工具编排（本地实现 ↔ 外部 MCP Server 可切换）

40 个工具通过 `@AgentTool` 注解自动注册进 ToolRegistry 白名单，未标注的方法默认拒绝；搜索/抠图/衣橱分析/试衣/天气均支持在"本地实现"和"MCP Server"两种 Provider 之间按配置切换。

- [ToolRegistry](src/main/java/com/wechatbot/fashion/ai/orchestration/ToolRegistry.java) / [McpToolSupport](src/main/java/com/wechatbot/fashion/ai/mcp/McpToolSupport.java)
- [MCP Server（Python，含抠图/试衣/识别/天气/搜索）](mcp-server/server.py)

### 5. 工程化兜底（微信实时产品必须的可靠性）

异步任务与超时控制、消息去重、会话隔离与限流、异常降级机制、Flyway 版本化迁移贯穿整个系统——这是从"能跑的 demo"到"能扛真实用户"的分水岭。

---

## RAG 检索评测

检索质量不是拍脑袋，而是基于真实用户查询的**可复现评测闭环**：

| 项 | 说明 |
| --- | --- |
| 数据 | `logs/eval_rerank.tsv`：57 条真实用户穿搭查询，GT = 系统实际采纳的参考穿搭编号（自动标注，无人工偏差） |
| 口径 | top-1 / top-5 / top-20 命中率；检索词由真实 QueryAnalyzer（贪婪采样 + 缓存）构造，与线上完全一致 |
| 当前基线 | gt 精确命中 top-5 **24.6%**（14/57，3 轮重测 24.6%~26.3%、波动 ≤1 条）；候选池 top-20 覆盖 70.2%；MySQL FULLTEXT 兜底对照仅 3.7% |
| 回归 | Java 侧 `ResumeBenchmarkLiveTest`（`RESUME_BENCH_LIVE=true` + `RESUME_BENCH_RAG_PROVIDER=ragflow` 时运行）；Python 侧统一入口见下 |

### 运行统一评测

```bash
# 前置：Docker Desktop + RAGFlow（9380）已启动；密钥从环境变量注入
export RAGFLOW_API_KEY='ragflow-xxxx'
export RAGFLOW_DATASET_ID='xxxx'
python scripts/eval/run.py                  # V1 线上口径 + V2/V3 消融对照，输出 reports/rag-eval-日期.md
python scripts/eval/run.py --variants v1    # 只跑线上口径 V1
```

> **数据隐私**：评测 TSV 含真实用户文本，不入库，仅存在于本地 `logs/`（已 gitignore）；报告只写索引号与 GT 编号，不透出原文。
> **密钥纪律**：所有评测脚本的密钥一律从环境变量读取（`RAGFLOW_API_KEY` / `RAGFLOW_DATASET_ID`），禁止硬编码进公开仓库。
> 历史 A/B 结论：rerank 与 query 改写均做过受控 A/B 且被数据否定（负收益），详见 [RAG 检索基准设计](docs/rag-benchmark-design.md)。

---

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.0（必需，Flyway 自动建表）
- Python 3.10+（必需，运行 MCP Server：抠图/试衣/识别/天气/搜索）
- Redis（可选，停机时自动降级）
- FFmpeg（视频处理用，可选）
- Docker（Qdrant 语义检索用，可选）

### 配置

复制配置模板并填入你的密钥：

```bash
cp src/main/resources/application-local.template.properties src/main/resources/application-local.properties
```

必填项：

```properties
# 阿里云百炼 — 文字聊天 + 多模态识别（OpenAI 兼容接口）
spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
spring.ai.openai.api-key=sk-your-dashscope-key
spring.ai.openai.chat.options.model=qwen3.7-plus

# iLink 微信机器人
ilink.enabled=true

# 本地管理站
app.admin.enabled=true
app.admin.username=admin
app.admin.password=your-strong-password
app.admin.session-encryption-key=your-32-byte-base64-key

# MySQL
app.persistence.enabled=true
app.persistence.jdbc-url=jdbc:mysql://127.0.0.1:3306/wechatbot?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
app.persistence.username=root
app.persistence.password=your-mysql-password

# 图片生成/编辑（OpenAI Images 兼容接口）
openai.image.base-url=https://your-image-provider.example/v1
openai.image.api-key=sk-your-image-key
app.ai.image-model=gpt-image-2
```

可选项（按需开启）：百炼 TTS、腾讯云 ASR、阿里云 OSS、博查搜索、Qdrant 语义检索、RAGFlow、高德地图等，详见 `application-local.template.properties`。

### 启动 MCP Server（必需）

项目默认全量走 MCP provider（搜索/抠图/识别/试衣/天气）。**Spring Boot 启动前必须先启动 MCP Server**，否则会因连不上 8090 启动失败（`Client failed to initialize`）。

```bash
cd mcp-server
python -m venv .venv
# Windows: .venv\Scripts\activate ；macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt

# 复制模板并填入 Key
copy .env.example .env          # Windows；macOS/Linux 用 cp .env.example .env
# 必填：DASHSCOPE_API_KEY（百炼）、ARK_API_KEY（火山引擎，虚拟试衣）
# 可选：BOCHA_API_KEY（搜索，不填自动用 cn.bing.com 免费搜索）

# 启动（监听 8090）
.venv\Scripts\python server.py
```

u2net 抠图模型（约 170MB）：首次调用 rembg 时自动下载到 `~/.u2net`。国内网络直连 GitHub 易超时，可设 `U2NET_HOME` 指向已下载目录，或从 `ghfast.top` 等镜像手动下载 `u2net.onnx` 放入该目录。

### 可选：RAGFlow 穿搭知识检索

RAGFlow 为 FashionAgent 提供穿搭知识库向量检索（出方案前检索穿搭规则）。默认 `app.fashion.rag.provider=mysql`（MySQL FULLTEXT 降级）；启用 RAGFlow 需先部署服务（默认 `http://127.0.0.1:9380`）并创建知识库数据集：

```properties
app.fashion.rag.provider=ragflow
# api-key 与 dataset-id 也可用环境变量 RAGFLOW_API_KEY / RAGFLOW_DATASET_ID 注入
app.fashion.rag.ragflow.api-key=ragflow-xxxxxxxxxxxxxxxx
app.fashion.rag.ragflow.dataset-id=xxxxxxxxxxxxxxxxxxxxxxxx
# 可选调优
app.fashion.rag.ragflow.top-k=5
app.fashion.rag.ragflow.similarity-threshold=0.2
app.fashion.rag.ragflow.vector-similarity-weight=0.3
app.fashion.rag.ragflow.timeout=15s
```

### 可选：阿里云 OSS（图片/文档存储）

默认图片保存在本地磁盘；开启 OSS 后，生成/上传的图片与文档改走阿里云对象存储，并可经 CDN 公网 URL 下发微信：

```properties
oss.image.enabled=true
oss.image.endpoint=oss-cn-{region}.aliyuncs.com
oss.image.access-key-id=LTAIxxxxxxxxxx
oss.image.access-key-secret=xxxxxxxxxxxxxxxx
oss.image.bucket-name=your-bucket
oss.image.prefix=ilink-bot/images
oss.image.signed-url-ttl=10m
# 文档存储（可选，默认关闭）
oss.document.enabled=false
oss.document.prefix=ilink-bot/documents
```

### 启动顺序

1. 启动 MySQL（Flyway 自动建表）
2. 启动 MCP Server（`mcp-server`，8090）
3. 启动 Spring Boot

```bash
mvn spring-boot:run -Dmaven.test.skip=true
```

启动后控制台输出二维码，微信扫码登录即可开始对话。

### Profile

| Profile | 用途 | 说明 |
| --- | --- | --- |
| `local` | 全功能本地开发（默认） | 加载被 Git 忽略的 `application-local.properties` |
| `minimal` | 仅穿搭管道，无媒体处理 | 关闭图片/视频/ASR/TTS |

```bash
# 仅穿搭的轻量实例
mvn spring-boot:run -Dspring-boot.run.profiles=minimal
```

---

## Docker 一键启动

提供全栈编排（app + MySQL + Redis + Qdrant + MCP Server），一行命令拉起：

```bash
cp .env.example .env     # 填写 DASHSCOPE_API_KEY 等（密钥只存本地 .env，不入库）
docker compose up -d --build
```

- 应用默认监听 `127.0.0.1:8080`，启动后访问 `http://127.0.0.1:8080`（管理站）或 `http://127.0.0.1:8080/actuator/health` 验证
- 应用依赖 MySQL 与 MCP Server 健康检查通过后才启动；Flyway 自动建表
- `docker compose logs -f app` 查看启动日志；MCP Server 首启会下载 u2net 抠图模型，稍慢属正常
- 想接真实微信时把 `.env` 里 `ILINK_ENABLED=true` 并补 iLink 配置
- 停服保留数据：`docker compose down`（数据卷保留）；彻底清理 `docker compose down -v`

`app` 镜像为**多阶段构建**（Maven 打包 → JRE 运行），MCP Server 为独立 Python 镜像（FastMCP + hypercorn，暴露 streamable-http）。

---

## 工具清单（40 个）

工具通过 `@AgentTool` 注解自动注册到 `ToolRegistry`，未标注的 `@Tool` 方法默认拒绝：

| 工具 | 所属类 | 功能 |
| --- | --- | --- |
| `fashion_consultant` | `FashionAgentService` | 穿搭多 Agent 管道入口 |
| `search_wardrobe_semantic` | `FashionSemanticTools` | 衣橱语义搜索（Qdrant） |
| `add_wardrobe_item` | `FashionTools` | 添加衣橱单品 |
| `search_wardrobe` | `FashionTools` | 搜索衣橱单品 |
| `get_fashion_profile` | `FashionTools` | 查看穿搭偏好画像 |
| `delete_wardrobe_item` | `FashionTools` | 归档衣橱单品 |
| `purge_wardrobe_item` | `FashionTools` | 彻底删除衣橱单品 |
| `virtual_try_on_wardrobe_item` | `FashionTryOnTools` | 提交虚拟试衣任务 |
| `check_virtual_tryon_status` | `FashionTryOnTools` | 查询试衣任务状态 |
| `virtual_try_on_reference_outfit` | `FashionTryOnTools` | 参考穿搭整套试穿 |
| `list_person_tryon_templates` | `FashionPersonTemplateTools` | 列出人物模板 |
| `save_person_tryon_template` | `FashionPersonTemplateTools` | 保存人物模板 |
| `select_person_tryon_template` | `FashionPersonTemplateTools` | 选择启用的人物模板 |
| `select_wardrobe_preview_item` | `FashionVisualPreviewTools` | 预览衣橱单品 |
| `show_current_tryon_template` | `FashionVisualPreviewTools` | 展示当前试衣模板 |
| `show_wardrobe_items` | `FashionVisualPreviewTools` | 展示衣橱列表 |
| `analyze_wardrobe_photo` | `FashionWardrobeIntakeTools` | 分析服装照片 |
| `submit_garment_cutout` | `FashionWardrobeIntakeTools` | 提交服装抠图 |
| `edit_garment_draft` | `FashionWardrobeIntakeTools` | 编辑草稿标签 |
| `retry_garment_cutout` | `FashionWardrobeIntakeTools` | 重试抠图 |
| `confirm_wardrobe_candidate` | `FashionWardrobeIntakeTools` | 确认入衣橱 |
| `cancel_wardrobe_candidate` | `FashionWardrobeIntakeTools` | 取消候选 |
| `list_wardrobe_photo_candidates` | `FashionWardrobeIntakeTools` | 列出待确认候选 |
| `update_wardrobe_candidate_labels` | `FashionWardrobeIntakeTools` | 更新候选标签 |
| `preview_garment_draft_version` | `FashionWardrobeIntakeTools` | 预览草稿版本 |
| `list_garment_draft_versions` | `FashionWardrobeIntakeTools` | 列出草稿版本 |
| `recommend_outfits_from_wardrobe` | `FashionOutfitRecommendationTools` | 衣橱搭配推荐 |
| `search_fashion_references` | `FashionReferenceTools` | 公共参考库搜索 |
| `search_web` | `McpWebSearchTools` | 联网搜索（MCP provider） |
| `generate_image` | `ImageTools` | 文生图 |
| `inspect_image` | `ImageTools` | 视觉识别图片内容 |
| `get_current_image` | `ImageTools` | 获取当前图片 |
| `list_recent_images` | `ImageTools` | 列出最近图片 |
| `create_image_revision` | `ImageTools` | 基于已保存图片改图 |
| `restore_image_version` | `ImageTools` | 回退图片版本 |
| `get_current_weather` | `WeatherTools` | 查询实时天气 |
| `get_current_china_time` | `ChinaTimeTools` | 获取中国当前时间 |
| `create_scheduled_agent_task` | `ReminderTools` | 创建定时提醒 |
| `list_wechat_reminders` | `ReminderTools` | 列出提醒 |
| `cancel_wechat_reminder` | `ReminderTools` | 取消提醒 |

---

## 项目结构

```
src/main/java/com/wechatbot/fashion/
├── ai/                         # AI 智能层
│   ├── config/                 # AI/图片/OSS/追踪等配置属性
│   ├── fashion/
│   │   └── look/               # 穿搭多 Agent 管道
│   │       ├── agent/          # AgentCoordinator + Stylist/Critic/Trend/Coordinator
│   │       ├── knowledge/      # 穿搭知识库 schema 初始化
│   │       ├── model/          # Agent 输入输出模型（结构化 JSON 协议）
│   │       ├── profile/        # 用户画像 + Embedding 服务
│   │       ├── rag/            # QueryAnalyzer + RAGFlow / MySQL FTS 知识检索
│   │       └── ...             # FashionAgentService / 反馈记录 / 参考图门控
│   ├── mcp/                    # MCP 工具支持（McpToolSupport）
│   ├── orchestration/          # Agent 编排（ToolRegistry / AgentSessionContext / @AgentTool）
│   ├── provider/               # 图片生成供应商（DashScope）
│   ├── service/                # 模型网关 + 会话管理 + 用量计费
│   └── tool/                   # 白名单 @Tool 工具类
├── admin/                      # 本地管理站（多实例管理 + 安全配置）
├── bot/                        # 微信机器人层（接入/路由/回复/音频/视频/文件）
├── common/                     # 通用并发工具
├── exchange/                   # 汇率查询
├── wardrobe/                   # 衣橱/试衣/推荐业务
│   ├── application/            # 应用服务（试衣/抠图/推荐/语义搜索 + MCP 适配器）
│   ├── config/                 # 穿搭配置属性
│   ├── domain/                 # 领域模型
│   ├── identity/               # 用户身份作用域
│   ├── persistence/            # JDBC 仓储
│   ├── runtime/                # 事件 + 调度器
│   ├── tool/                   # 穿搭相关 @Tool 工具类
│   └── web/                    # 管理 Controller
├── iplocation/                 # IP 定位
├── location/                   # 位置搜索
├── navigation/                 # 高德导航/路线规划
├── persistence/                # 对话/图片/文档持久化（MySQL + Redis）
├── reminder/                   # 微信定时提醒
├── schedule/                   # 定时任务调度
├── search/                     # 网页搜索
├── storage/                    # 本地磁盘工作区 + 定时清理
├── tianxing/                   # 星座/食谱/景区/历史今日等
├── weather/                    # 天气查询（uapis / MCP）
└── WeChatBotApplication.java   # 启动入口
```

---

## 数据流

```
微信消息 → ILinkBotService（去重 → 限流 → 线程池）
  → ILinkReplyService（消息类型路由）
    → AiChatService.answer()（多轮记忆 + 用量预算）
      → RoutingLlmGateway（协议选择）
        → SpringAiChatCompletionsGateway（Function Calling）
          → ToolRegistry 白名单工具（模型按需调用）
            → fashion_consultant → AgentCoordinator 多 Agent 管道
            → virtual_try_on_wardrobe_item → FashionVirtualTryOnService
            → generate_image → AiImageGenerationService（异步）
            → ...
    → ILinkBotService 发送回复
    → 后台任务完成后异步补发图片
```

---

## 构建与测试

```bash
# 编译打包
mvn clean package -Dmaven.test.skip=true

# 运行
java -jar target/wechatbot-0.0.1-SNAPSHOT.jar

# 运行测试
mvn test
```

CI 流水线配置见 `.github/workflows/build.yml`。

---

## 技术栈

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | 应用框架 |
| Spring AI | 1.1.8 | OpenAI 兼容接口 + Function Calling + Qdrant + MCP Client |
| weixin-ilink-sdk | 1.0.0 | 微信 iLink 协议 |
| openai-java | 4.43.0 | OpenAI 兼容客户端（Responses 通道） |
| Qdrant | gRPC 1.65.1 | 衣橱语义向量检索 |
| MySQL + Flyway | 8.0 | 持久化 + 自动迁移（V24） |
| Redis | — | 消息去重/限流/缓存（可选） |
| 阿里云 OSS | 3.17.4 | 图片/文档存储 |
| 阿里云百炼 | — | 文字模型 / 图片生成 / Embedding / TTS |
| 腾讯云 ASR | 3.1.1500 | 语音识别 |
| Apache PDFBox | 3.0.5 | PDF 文本提取 |
| x-easypdf-pdfbox | 3.5.5 | PDF 生成（内置中文字体） |
| Apache POI | 5.4.1 | Office 文档读写 |
| FFmpeg | — | 视频抽帧 + 音频提取 |
| ZXing | 3.5.3 | 二维码生成（iLink 登录） |

---

## 文档导航

详细文档位于 `docs/` 目录，导航索引见 [docs/README.md](docs/README.md)。

| 主题 | 文档 |
| --- | --- |
| 入门运行 | [iLink 指南](docs/getting-started/ILINK_GUIDE.md) |
| 代码导读 | [代码走读](docs/getting-started/ILINK_CODE_WALKTHROUGH.md) |
| 项目结构 | [结构说明](docs/architecture/PROJECT_STRUCTURE.md) |
| Agent 编排 | [编排设计](docs/AGENT_ORCHESTRATION.md) → [穿搭 Agent 设计](docs/fashion-agent-design.md) |
| 穿搭业务 | [业务逻辑](docs/fashion-business-logic.md) → [演示用例](docs/features/FASHION_AGENT_DEMO_CASES.md) |
| RAG 检索 | [RAG 优化完整记录](docs/rag-optimization-full-record.md) → [衣橱 RAG 设计](docs/wardrobe-rag-design.md) |
| MCP 替换方案 | [01-网页搜索](docs/mcp-replacement-01-web-search.md) → [02-抠图](docs/mcp-replacement-02-garment-cutout.md) → [03-衣橱分析](docs/mcp-replacement-03-wardrobe-analysis.md) → [04-天气](docs/mcp-replacement-04-weather.md) → [05-虚拟试衣](docs/mcp-replacement-05-virtual-tryon.md) |
| 统筹方案 | [穿搭统筹方案](docs/fashion-master-plan.md) |

---

## 工程约定

- **配置属性类**通过 `@EnableConfigurationProperties` 在启动类集中注册，不使用 `@Component`
- **`@Scheduled`** 使用 `${...}` 属性占位符，不使用 SpEL `#{@beanName.xxx()}` 避免 bean 命名耦合
- **MCP 客户端**返回失败结果而非抛出异常，防止调度器中断
- **MCP Provider** 实现优先使用 `imageBase64` 而非 `imageUrl`，避免不必要的网络下载
- **业务调用方**使用 `AgentSessionContext.requireUserId()` 而非 `currentUserId()`，防止会话上下文未初始化时的数据交叉污染
- **穿搭推荐**必须包含 `referenceOutfitId`，确保文字描述与参考图对齐