# 项目结构说明

## 一句话理解

这个项目分为两条主线：`bot` 负责“从微信收消息、处理媒体、把结果回给微信”，`ai` 负责“保存短期文本上下文、选择模型协议、调用大模型”。`weather` 是一个独立业务服务示例，展示未来 Agent Tool 应如何连接真实业务。

## 源码包职责

```text
com.example.ykdsummer
├── YkdSummerApplication.java     启动 Spring Boot
├── ai/
│   ├── config/                   模型地址、模型名、超时等配置绑定
│   ├── model/                    图片、文件、对话消息等传输对象
│   ├── service/                  上下文、Responses、Completion、路由、生图
│   └── tool/                     只给 Agent/模型调用的工具方法
├── bot/
│   ├── config/                   iLink、限流、语音、视频、文件配置
│   ├── controller/               本地状态、二维码等 HTTP 接口
│   ├── service/                  接收消息、分流、下载、回复、限流
│   ├── message/ runtime/ session/ 消息类型、运行状态、登录会话和游标
│   ├── audio/ video/             语音转写、TTS、抽帧、视频分析
│   ├── document/ file/           文本提取、文件渲染、一次性文件会话
│   └── ...
└── weather/                      独立天气业务 Service 和返回对象
```

## 一条普通消息怎样走

```text
微信 iLink
  → ILinkBotService（收到消息、排队）
  → ILinkReplyService（判断文字/图片/文件/语音/视频）
  → AiChatService（维护该用户短期文本历史）
  → RoutingLlmGateway
      ├─ 纯文本：SpringAiChatCompletionsGateway（可调用 Tool）
      └─ 图片、文件、视频帧：OpenAiResponsesGateway
  → ILinkBotService（发送文字、图片或文件给微信）
```

## 以后加功能放哪里

| 需求 | 放置位置 |
| --- | --- |
| 新增普通聊天能力、模型协议或上下文策略 | `ai/service` |
| 新增 Agent 可调用业务能力 | `ai/tool`，业务代码仍放自己的 `xxx/service` |
| 新增课程、订单、天气等业务 | 独立 `course`、`order`、`weather` 包；不要塞进 `bot` |
| 新增微信消息类型或回复方式 | `bot/service`、`bot/message` |
| 新增音频、视频、文件处理 | 对应 `bot/audio`、`bot/video`、`bot/file` |
| 新增数据库 CRUD | `xxx/service` + `xxx/mapper` + `xxx/entity`；Tool 只调用 Service |

## 推荐阅读顺序

1. `YkdSummerApplication`：项目怎么启动。
2. `bot/service/ILinkBotService`：怎么连微信、接收消息、发送回复。
3. `bot/service/ILinkReplyService`：不同消息怎样分流。
4. `ai/service/AiChatService`：文本上下文怎么保存。
5. `ai/service/RoutingLlmGateway`：为什么文本走 Completion、媒体走 Responses。
6. `ai/tool/WeatherTools`：未来 Agent 调用 Java 工具的写法。

## 运行期目录

- `.ilink/`：登录会话和游标，删除后需要重新扫码。
- `.documents/`：用户文件处理的临时数据，已被 Git 忽略。
- `target/`：Maven 构建结果，可随时重新生成。
- `.idea/`：你电脑上的 IDEA 配置。

它们都不是业务源码，Git 已忽略其中大部分本地数据。
