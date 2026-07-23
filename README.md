# ykd-summer — 微信 AI 机器人

基于 Spring AI 1.1.8 + 6 层 Agent 架构的微信智能助手。支持图片生成/识别、文档处理（PDF/DOCX/XLSX/PPTX）、语音合成/识别、视频分析、天气查询、联网搜索等能力，所有 AI 功能通过 `@Tool` 机制统一暴露给大模型调用。

---

## 架构

```
微信消息
  │
  ├─ [接入层]  ILinkBotService    — SDK 长轮询 + 去重 + 限流 + 线程池
  │
  ├─ [路由层]  ILinkReplyService  — 消息类型分流（文件/命令/语音/视频/文字/图片）
  │
  ├─ [编排层]  AgentCoordinator   — 工作区管理 + ThreadLocal 上下文 + 结果分发
  │
  ├─ [会话层]  AiChatService      — Caffeine 多轮记忆 + 顺序保证
  │
  ├─ [网关层]  SpringAiChatCompletionsGateway — Spring AI Function Calling + Tools
  │
  └─ [工具层]  11 个 @Tool        — 天气 / 搜索 / 文档 / 图片 / 语音 / 视频
```

### 6 层职责

| 层         | 模块                                                 | 职责                                                         |
| ---------- | ---------------------------------------------------- | ------------------------------------------------------------ |
| **接入层** | `bot.service.ILinkBotService`                        | iLink SDK 长轮询接收消息，去重/限流/线程池分发               |
| **路由层** | `bot.service.ILinkReplyService` + `MessageExtractor` | 解析消息类型，路由到不同处理分支                             |
| **编排层** | `ai.orchestration.AgentCoordinator`                  | 创建工作区、设置 ThreadLocal 上下文、协调 LLM 调用和工具结果 |
| **会话层** | `ai.service.AiChatService`                           | Caffeine 管理用户多轮对话历史，`synchronized` 保证顺序       |
| **网关层** | `ai.service.SpringAiChatCompletionsGateway`          | Spring AI `ChatClient` 调用，注册 `@Tool` Bean               |
| **工具层** | `ai.tool.*`                                          | 11 个 `@Tool` 注解方法，由 LLM 通过 Function Calling 调用    |

---

## 工具清单

| 工具                  | 类                     | 功能                                             |
| --------------------- | ---------------------- | ------------------------------------------------ |
| `get_current_weather` | `WeatherTools`         | 查询城市实时天气                                 |
| `search_web`          | `WebSearchTools`       | SerpAPI 互联网搜索                               |
| `generate_document`   | `DocumentTools`        | 生成 PDF/DOCX/XLSX/PPTX/MD/TXT/CSV/JSON/XML/HTML |
| `read_document`       | `DocumentTools`        | 读取 PDF/DOCX/XLSX/PPTX/TXT 内容                 |
| `edit_document`       | `DocumentTools`        | 编辑已有 Office 文档                             |
| `convert_document`    | `DocumentTools`        | 文档格式转换                                     |
| `generate_image`      | `ImageGenerationTools` | 根据文字生成图片                                 |
| `analyze_image`       | `ImageAnalysisTools`   | 识别/分析图片内容                                |
| `analyze_video`       | `VideoAnalysisTools`   | 视频画面 + 音频分析                              |
| `transcribe_audio`    | `AsrTools`             | 语音文件转文字                                   |
| `text_to_speech`      | `TtsTools`             | 文字合成语音                                     |

---

## 快速开始

### 环境要求

- JDK 21
- Maven 3.9+
- FFmpeg（视频处理用）

### 配置

复制 `application-local.properties` 并按需修改：

```properties
# === 文字聊天 — 百炼 OpenAI 兼容接口 ===
spring.ai.openai.api-key=${DASHSCOPE_API_KEY}
spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
spring.ai.openai.chat.options.model=qwen3.7-plus

# === 图片生成 ===
app.ai.image-api-key=${DASHSCOPE_API_KEY}
app.ai.image-model=wanx2.1-t2i-turbo

# === 语音识别（腾讯云）===
tencentcloud.asr.secret-id=${TENCENTCLOUD_SECRET_ID}
tencentcloud.asr.secret-key=${TENCENTCLOUD_SECRET_KEY}

# === 微信机器人 ===
ilink.enabled=true
ilink.account=你的微信账号
```

### 运行

```bash
mvn spring-boot:run -Dmaven.test.skip=true
```

启动后，控制台输出二维码，微信扫码即可登录。

---

## 项目结构

```
src/main/java/com/example/ykdsummer/
├── ai/                  # AI 智能层
│   ├── config/          # AI 配置绑定
│   ├── model/           # 数据传输对象
│   ├── orchestration/   # Agent 编排层（Coordinator / Registry / Context）
│   ├── provider/        # 图片生成供应商
│   ├── service/         # 模型网关 + 会话管理
│   └── tool/            # 8 个 @Tool 工具类
├── bot/                 # 微信机器人层
│   ├── audio/           # 语音合成（TTS）+ 语音识别（ASR）
│   ├── config/          # 机器人配置
│   ├── controller/      # HTTP 管理接口
│   ├── document/        # 文档文本提取 + 渲染
│   ├── file/            # 文件会话管理
│   ├── message/         # 消息解析 + 命令处理 + 去重
│   ├── runtime/         # 运行状态
│   ├── service/         # SDK 生命周期 + 消息路由 + 回复
│   ├── session/         # SDK 会话持久化
│   └── video/           # 视频抽帧 + 分析
├── storage/             # 本地磁盘工作区 + 定时清理
└── weather/             # 天气查询业务
```

---

## 如何新增一个 Tool

1. 在 `ai/tool/` 下创建 `@Component` 类
2. 方法标注 `@Tool(name="xxx", description="...")`
3. 参数标注 `@ToolParam(required=true, description="...")`
4. 重启即可，`ToolRegistry` 自动注册

示例：

```java
@Component
public class ExampleTools {

    @Tool(name = "example_function", description = "示例工具描述")
    public String exampleFunction(
            @ToolParam(required = true, description = "参数说明")
            String param
    ) {
        return "处理结果";
    }
}
```

---

## 数据流

```
微信消息 → ILinkBotService（去重 → 限流 → 线程池）
  → ILinkReplyService（消息类型路由）
    → AgentCoordinator.execute()
      → AiChatService.answer()（多轮记忆）
        → SpringAiChatCompletionsGateway（Function Calling）
          → @Tool Bean（LLM 按需调用）
      ← 检查 PendingImage / PendingDocument
    → ILinkBotService 发送回复
```

---

## 构建

```bash
mvn clean package -Dmaven.test.skip=true
java -jar target/ykd-summer-0.0.1-SNAPSHOT.jar
```

---

## 依赖

| 依赖               | 用途                               |
| ------------------ | ---------------------------------- |
| Spring Boot 3.5.16 | 应用框架                           |
| Spring AI 1.1.8    | OpenAI 兼容接口 + Function Calling |
| weixin-ilink-sdk   | 微信 iLink 协议                    |
| x-easypdf-pdfbox   | PDF 生成                           |
| Apache PDFBox      | PDF 文本提取                       |
| Apache POI         | Office 文档读写                    |
| FFmpeg             | 视频抽帧 + 音频提取                |
| 阿里云百炼         | 文字模型 / 图片生成 / 语音合成     |
| 腾讯云 ASR         | 语音识别                           |
| SerpAPI            | 互联网搜索                         |
