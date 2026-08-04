# 项目结构说明

## 一句话理解

这个项目以**时尚穿搭 Agent**为核心业务，`ai` 负责 AI 能力（模型网关、多 Agent 编排、RAG 检索、工具注册），`fashion` 负责穿搭业务域（衣橱、试衣、推荐、参考库），`bot` 负责微信 iLink 接入与媒体处理。其余包为辅助/历史能力。

## 源码包职责

```text
com.example.ykdsummer
├── YkdSummerApplication.java        启动类，集中注册 @ConfigurationProperties
├── ai/                              AI 能力层
│   ├── config/                      模型客户端、OSS、用量、链路追踪配置
│   ├── service/                     LLM/视觉/图像网关、用量计量、资产存储
│   ├── tool/                        供模型调用的工具方法（搜索/图像/天气）
│   ├── mcp/                         MCP 共享工具（McpToolSupport）
│   ├── orchestration/               Agent 编排基础设施（ToolRegistry、AgentSessionContext）
│   ├── model/                       AI 通用传输对象
│   ├── provider/                    模型供应商适配（DashScope）
│   └── fashion/                     时尚 Agent 大脑（编排/RAG/画像/DTO）
│       ├── agent/                   多 Agent 管道（Stylist/Critic/Trend/Coordinator）
│       ├── rag/                     时尚知识检索（RAGFlow / MySQL FTS 双后端）
│       ├── profile/                 用户画像、对话记忆、Embedding
│       └── model/                   时尚 Agent DTO
├── fashion/                         时尚穿搭业务域（DDD 分层，体量最大）
│   ├── domain/                      领域模型/枚举/值对象
│   ├── application/                 应用服务（用例编排，含 MCP 多策略实现）
│   ├── persistence/                 仓储接口 + Jdbc 实现
│   ├── tool/                        暴露给 Agent 的 @Tool 集合
│   ├── runtime/                     异步任务派发器与完成事件
│   ├── config/                      时尚域配置
│   ├── identity/                    用户范围解析
│   └── web/                         时尚后台 HTTP
├── bot/                             微信 iLink 接入层
│   ├── service/                     消息接收/分流/下载/回复/限流
│   ├── audio/                       语音转写(ASR)、TTS
│   ├── video/                       视频抽帧、分析
│   ├── config/                      iLink/限流/ASR/TTS/视频/文件配置
│   ├── message/ runtime/ session/   消息类型、运行状态、登录会话
│   ├── document/ file/              文本提取、文件渲染、一次性文件会话
│   └── controller/                  HTTP 接口（状态/二维码）
├── persistence/                     通用持久化（会话历史、资产元数据、Redis）
├── reminder/                        提醒业务域（DDD 分层）
├── schedule/                        动态定时任务调度
├── admin/                           多实例管理后台
├── storage/                         文件存储与清理
├── weather/                         天气业务（含 MCP Provider）
├── tianxing/                        天行 API 娱乐能力（星座/菜谱/景点/历史）
├── search/ location/ navigation/    独立小业务 Service
├── exchange/                        汇率查询
└── common/concurrent/               通用并发工具
```

## 一条普通消息怎样走

```text
微信 iLink
  → ILinkBotService（收到消息、排队）
  → ILinkReplyService（判断文字/图片/文件/语音/视频）
  → AiChatService（维护该用户短期文本历史）
  → RoutingLlmGateway
      ├─ 纯文本：SpringAiChatCompletionsGateway（通过 ToolRegistry 调用 @AgentTool 工具）
      └─ 图片、文件、视频帧：OpenAiResponsesGateway
  → 穿搭请求自动触发 FashionAgentService → AgentCoordinator（5 步多 Agent 管道）
  → ILinkBotService（发送文字、图片或文件给微信）
```

## 工具注册机制

- `ToolRegistry`：唯一权威注册源，在 `ContextRefreshedEvent` 时扫描所有标有 `@AgentTool` 注解的 `@Tool` 方法
- **默认拒绝**：未标 `@AgentTool` 的工具不会被 Agent 调用，避免聊天模型获得无关能力
- `SpringAiChatCompletionsGateway` 通过 `toolRegistry.allToolBeans()` 懒加载工具

## 以后加功能放哪里

| 需求 | 放置位置 |
| --- | --- |
| 新增聊天能力、模型协议或上下文策略 | `ai/service` |
| 新增 Agent 可调用业务能力 | 在业务包中标 `@AgentTool` + `@Tool`，ToolRegistry 自动发现 |
| 新增穿搭业务用例 | `fashion/application`（应用服务）+ `fashion/domain`（领域模型） |
| 新增微信消息类型或回复方式 | `bot/service`、`bot/message` |
| 新增音频、视频、文件处理 | 对应 `bot/audio`、`bot/video`、`bot/file` |
| 新增数据库迁移 | `src/main/resources/db/migration/V{N}__*.sql` |

## 推荐阅读顺序

1. `YkdSummerApplication`：项目怎么启动、配置怎么注册。
2. `bot/service/ILinkBotService`：怎么连微信、接收消息、发送回复。
3. `bot/service/ILinkReplyService`：不同消息怎样分流。
4. `ai/service/AiChatService`：文本上下文怎么保存。
5. `ai/service/RoutingLlmGateway`：为什么文本走 Completion、媒体走 Responses。
6. `ai/fashion/agent/AgentCoordinator`：穿搭多 Agent 管道怎么编排。
7. `ai/orchestration/ToolRegistry`：工具注册和 @AgentTool 白名单机制。

## 运行期目录

- `.ilink/`：登录会话和游标，删除后需要重新扫码。
- `.documents/`：用户文件处理的临时数据，已被 Git 忽略。
- `target/`：Maven 构建结果，可随时重新生成。
- `data/`：穿搭 RAG 文档（`fashion_docs/`）、图片 URL 映射（`image_urls.json`）、种子数据。
