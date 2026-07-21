# 项目重写工单

## 概述

从零重写 WeChat + DashScope AI 聊天机器人项目，按如下工单顺序逐步构建。每个工单有明确的**范围**、**交付物**和**验收标准**，工单之间可独立开发和测试。

---

## 阶段 1：项目骨架

### 工单 1 — Spring Boot 项目初始化

| 项目 | 说明 |
|------|------|
| **目标** | 创建可运行的 Spring Boot 空项目 |
| **范围** | pom.xml、主入口类、配置文件、健康检查端点 |
| **分支** | `stage-01-skeleton` |

**具体任务：**
- [ ] 创建 `pom.xml`，包含依赖：
  - `spring-boot-starter-parent:3.2+`
  - `spring-boot-starter-web`
  - `spring-boot-starter-test`
  - `wechat-ilink-sdk:2.3.3`
  - `jackson-databind`（Spring Boot 自带）
- [ ] 创建主入口 `Demo1Application.java`，加 `@SpringBootApplication`
- [ ] 创建 `application.properties`，配置端口、应用名
- [ ] 创建 `model/Message.java`（`String role, String content` 简单 POJO）
- [ ] 写一个 `GET /api/ping` 返回 `pong` 作为健康检查

**验收标准：**
- [ ] `mvnw compile` 编译通过
- [ ] `mvnw test` 通过
- [ ] `mvnw spring-boot:run` 启动后访问 `/api/ping` 返回 `pong`

---

## 阶段 2：AI 对话

### 工单 2 — DashScope LLM 文本对话

| 项目 | 说明 |
|------|------|
| **目标** | 完成 LLMService 核心对话能力 |
| **范围** | ai/LLMService.java、application.properties LLM 配置 |
| **分支** | `stage-02-llm-chat` |

**具体任务：**
- [ ] 在 `application.properties` 配置：
  - `llm.api-key=sk-xxx`
  - `llm.api-url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
  - `llm.model=qwen-plus`
  - `llm.temperature=0.8`
  - `llm.max-tokens=4096`
  - `llm.system-prompt=你是一个智能助手，请用中文简洁友好地回答问题。`
- [ ] 创建 `ai/LLMService.java`，注入以上配置
- [ ] 实现 `chat(String userMessage)` → 调用 DashScope OpenAI 兼容 API
- [ ] 实现 `chat(List<Message> history, String newMessage)` → 带历史上下文
- [ ] 实现 JSON 请求体构建（messages 数组，含 system/user/assistant 角色）
- [ ] 实现响应解析（从 `choices[0].message.content` 提取文本）
- [ ] 处理异常：401（认证失败）、429（频率限制）、网络超时、DNS 解析失败
- [ ] 所有 API 敏感信息通过 `application-local.properties` 覆盖，被 `.gitignore` 排除

**验收标准：**
- [ ] `chatService.chat("你好")` 返回非空的中文回答
- [ ] 带历史调用时，模型能引用上文（如记住用户名字）
- [ ] API Key 错误时返回友好错误提示，不抛异常
- [ ] 网络不可用时返回友好提示

---

### 工单 3 — 多轮会话记忆

| 项目 | 说明 |
|------|------|
| **目标** | 实现 SessionManager，支持每用户 20 轮会话记忆 |
| **范围** | chat/SessionManager.java |
| **分支** | `stage-03-session` |

**具体任务：**
- [ ] 创建 `chat/SessionManager.java`（`@Service`）
- [ ] 使用 `ConcurrentHashMap<String, Deque<Message>>` 存储
- [ ] 限制：每用户最多 20 轮（40 条消息），超出时淘汰最早消息
- [ ] 方法：
  - `addMessage(userId, role, content)` — 追加消息，超量时移除最早的
  - `getHistory(userId)` — 返回只读副本
  - `clearSession(userId)` — 清除会话
  - `getActiveSessionCount()` — 活跃会话数
  - `hasSession(userId)` — 是否存在会话

**验收标准：**
- [ ] 连续发送 25 轮消息后，getHistory 只返回最近 20 轮（40 条）
- [ ] 不同用户的会话互相隔离
- [ ] clearSession 后历史为空
- [ ] getActiveSessionCount 返回正确计数

---

### 工单 4 — 消息路由入口

| 项目 | 说明 |
|------|------|
| **目标** | 实现 CommandHandler，打通命令分发 + LLM 对话 |
| **范围** | chat/CommandHandler.java |
| **分支** | `stage-04-router` |

**具体任务：**
- [ ] 创建 `chat/CommandHandler.java`
- [ ] 实现 `handle(text, fromUserId)`：
  - 以 `/` 开头 → 路由到 CommandManager（后续工单实现）
  - 其他文本 → `sessionManager.addMessage("user", text)` → `llmService.chat(history, text)` → `sessionManager.addMessage("assistant", reply)` → 返回 reply
- [ ] 异常处理：LLM 调用失败时返回友好提示

**验收标准：**
- [ ] 非命令文本能正常走 LLM 对话并记入历史
- [ ] `/help` 等命令能正常路由（CommandManager 返回"未知命令"兜底）

---

## 阶段 3：命令系统

### 工单 5 — 命令框架

| 项目 | 说明 |
|------|------|
| **目标** | 实现命令策略模式和命令管理器 |
| **范围** | chat/command/ICommand.java, chat/CommandManager.java |
| **分支** | `stage-05-commands` |

**具体任务：**
- [ ] 创建 `chat/command/ICommand.java` 接口：
  - `String getName()` — 命令名（小写，无 `/`）
  - `String getDescription()` — 描述文本
  - `String execute(String[] args)` — 执行逻辑
- [ ] 创建 `chat/CommandManager.java`（`@Component`）：
  - `@Autowired(required=false) List<ICommand> commandList`
  - `@PostConstruct init()` — 注册所有 ICommand Bean 到 `Map<String, ICommand>`
  - `String dispatch(String input)` — 解析命令名和参数，查找并执行
  - `String getHelpText()` — 遍历所有命令生成帮助文本
  - `String getVersion()` — 返回版本号

**验收标准：**
- [ ] 注册新的 ICommand Bean 后，自动被 CommandManager 发现
- [ ] dispatch("/help") 调用 CommandManager.getHelpText()
- [ ] dispatch("/unknown_command") 返回 null 供调用方处理

---

### 工单 6 — 内置命令

| 项目 | 说明 |
|------|------|
| **目标** | 实现 4 个内置命令 |
| **范围** | chat/command/HelpCommand.java, VersionCommand.java, StatusCommand.java, WeatherCommand.java, weather/WeatherService.java |
| **分支** | `stage-06-commands-impl` |

**具体任务：**
- [ ] `HelpCommand` — 调用 `CommandManager.getHelpText()` 返回所有命令列表
- [ ] `VersionCommand` — 返回版本字符串 `WeChat iLink Bot\n版本: 1.0.0`
- [ ] 接入心知天气免费 API：
  - `weather.api.private-key` 配置
  - 调用 `https://api.seniverse.com/v3/weather/daily.json` 获取 3 天预报
  - 解析 JSON 响应，格式化输出（日期、温度、天气、风向、湿度）
  - 处理错误：城市不存在、API Key 无效、网络不可用等
- [ ] `WeatherCommand` — `/weather <城市名> [天数]`，支持多词城市名（如 `New York`）
- [ ] `StatusCommand` — 返回登录状态（`/status`)
  - 需要注入 ILinkService（下阶段实现，先留桩返回"未登录"）

**验收标准：**
- [ ] `/help` 列出所有可用命令
- [ ] `/version` 返回版本信息
- [ ] `/weather 北京` 返回北京天气预报
- [ ] `/weather New York 2` 返回纽约 2 天预报

---

## 阶段 4：微信集成

### 工单 7 — iLink SDK 登录与消息收发

| 项目 | 说明 |
|------|------|
| **目标** | 接入 iLink SDK，完成登录和消息收发 |
| **范围** | wechat/ILinkService.java |
| **分支** | `stage-07-ilink-baseline` |

**具体任务：**
- [ ] 创建 `wechat/ILinkService.java`
- [ ] `@PostConstruct init()` 方法中：
  - 网络预检：DNS 解析 `ilinkai.weixin.qq.com` + TCP 连接 `443` 端口
  - 创建 ILinkConfig（超时 35s、心跳 30s、重试 3 次）
  - 创建 ILinkClient
  - 设置 OnLoginListener（打印 botId）
  - 设置 OnMessageListener（后续工单实现）
  - 启动异步登录线程，输出二维码内容到控制台
- [ ] 基础发送方法（薄封装）：
  - `sendText(targetUserId, text)`
  - `sendTextWithTyping(targetUserId, text, typingMs)`
  - `sendImage(targetUserId, imageBytes, fileName, caption)`
  - `sendVoice(targetUserId, voiceBytes, fileName, playTimeMs, sampleRate)`
- [ ] `awaitLogin(timeout, unit)` — 等待登录完成
- [ ] `getBotId()` — 返回 botId
- [ ] `@PreDestroy destroy()` — 关闭 client
- [ ] 所有方法做 null/empty 守卫，异常只打日志不往外抛

**验收标准：**
- [ ] 启动后控制台输出二维码内容
- [ ] 扫码登录后日志显示 botId
- [ ] 调用 sendText 能成功发送消息
- [ ] 网络不可用时优雅降级（日志警告，不打断启动）

---

### 工单 8 — 文本消息处理

| 项目 | 说明 |
|------|------|
| **目标** | 实现文本消息接收 → 路由 → 回复的完整流程 |
| **范围** | ILinkService.handleIncomingMessage() 文本分支 |
| **分支** | `stage-08-text-message` |

**具体任务：**
- [ ] 在 OnMessageListener.onMessages 中遍历每条消息
- [ ] 解析 `item_list`，提取 `text_item.text`（多条文本拼接）
- [ ] 调用 `commandHandler.handle(text, fromUserId)`
- [ ] 根据回复内容选择发送方式：
  - 如果原始消息以 `语音回复：` 开头 → 走 TTS 语音回复（下阶段实现）
  - 否则 → `sendTextWithTyping(reply, 1500)`
- [ ] 特殊情况处理：图片消息、语音消息、视频消息（后续工单实现，先走 text-only fallback）

**验收标准：**
- [ ] 收到纯文本微信消息后，自动回复 AI 生成的文本
- [ ] 回复带有输入态效果

---

### 工单 9 — 图片消息处理

| 项目 | 说明 |
|------|------|
| **目标** | 多模态图片对话 |
| **范围** | ai/LLMService.chatWithImage()、ILinkService 图片分支 |
| **分支** | `stage-09-image-message` |

**具体任务：**
- [ ] `application.properties` 配置：
  - `llm.vision-model=qwen-vl-plus`
- [ ] LLMService 新增 `chatWithImage(history, text, imageBytes, mimeType)`：
  - 构建 vision 请求体：多模态 content 数组（含 `text` 和 `image_url`）
  - 图片 base64 编码，使用 `data:image/{type};base64,{data}` 格式
  - 调用 `POST {api-url}`，model 设为 `qwen-vl-plus`
  - 解析响应同 text chat
- [ ] ILinkService 实现图片接收：
  - `client.downloadImageFromMessageItem(imageItem)` 下载图片
  - MIME 类型通过魔数检测（PNG: 89 50 4E 47, JPEG: FF D8 FF）
  - 限制大小 10MB
  - 调用 `commandHandler.handle(text, fromUserId, imageBytes, mimeType)`
- [ ] CommandHandler 新增带图片的 handle 重载

**验收标准：**
- [ ] 发送图片后，LLM 能描述图片内容
- [ ] 大图片（>10MB）返回友好提示

---

### 工单 10 — 图片生成

| 项目 | 说明 |
|------|------|
| **目标** | 支持用户发送"生图：一只猫"触发 AI 图片生成 |
| **范围** | ai/ImageGenerationService.java、ILinkService 图片生成分支 |
| **分支** | `stage-10-image-gen` |

**具体任务：**
- [ ] `application.properties` 配置：
  - `llm.image-api-url=https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis`
  - `llm.image-task-url=https://dashscope.aliyuncs.com/api/v1/tasks`
  - `llm.image-model=wanx2.1-t2i-turbo`
  - `llm.image-size=1024*1024`
- [ ] 创建 `ai/ImageGenerationService.java`：
  - `submitTask(prompt)` → POST 提交文生图任务，返回 taskId
  - `waitForResult(taskId)` → 轮询任务状态（60 次 × 2s），下载图片
  - `generate(prompt)` → 组合以上两步，返回 `byte[]`
- [ ] ILinkService 检测 `生图：` / `生图:` 前缀，触发图片生成
- [ ] 生成过程中先回复"正在生成图片，请稍候..."

**验收标准：**
- [ ] 发送"生图：一只坐在窗边的橘猫"后，收到生成的图片
- [ ] 生成失败时返回友好提示

---

### 工单 11 — 语音消息处理（ASR）

| 项目 | 说明 |
|------|------|
| **目标** | 接收微信语音消息，ASR 转文字后走 LLM 对话 |
| **范围** | ai/SpeechRecognitionService.java、ILinkService 语音分支 |
| **分支** | `stage-11-asr` |

**具体任务：**
- [ ] `application.properties` 配置：
  - `llm.asr-api-url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
  - `llm.asr-model=qwen3-asr-flash`
- [ ] 创建 `ai/SpeechRecognitionService.java`：
  - `transcribe(audioBytes, mimeType)` 方法
  - 构建 `input_audio` 类型消息体，base64 编码音频数据
  - 从 `choices[0].message.content` 提取识别文本
- [ ] ILinkService：
  - `client.downloadVoiceFromMessageItem(voiceItem)` 下载语音
  - MIME 类型通过魔数检测（WAV: RIFF, OGG: OggS, MP3: ID3, AMR: #!AMR）
  - 限制大小 20MB
  - ASR 完成后回复标注 `【语音识别回复】` + LLM 回答（文本形式，避免语音闭环）

**验收标准：**
- [ ] 发送微信语音后，收到文字回复且带 `【语音识别回复】` 标记
- [ ] 太短的语音（<0.5s）返回"未识别到有效内容"

---

### 工单 12 — 语音回复（TTS）

| 项目 | 说明 |
|------|------|
| **目标** | 支持文本前缀触发 TTS 语音回复 |
| **范围** | ai/SpeechSynthesisService.java、ILinkService 语音回复分支 |
| **分支** | `stage-12-tts` |

**具体任务：**
- [ ] `application.properties` 配置：
  - `llm.tts-api-url=https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
  - `llm.tts-model=qwen3-tts-flash`
  - `llm.tts-voice=Cherry`
  - `llm.tts-max-chars=400`
  - `llm.tts-sample-rate=24000`
- [ ] 创建 `ai/SpeechSynthesisService.java`：
  - `synthesize(text)` 方法，调用 TTS API
  - 长文本分段（按 `。！？.!?\n` 分割，超长再按 `，,` 分割）
  - WAV 解析：`readWavMetadata()` 读取采样率、位深、时长
  - PCM 转 WAV 封装：`wrapPcmAsWav(pcmData, sampleRate, channels, bitsPerSample)`
  - 返回 `List<SynthesizedAudio>` 含 audioBytes、durationMs、sampleRate 等
- [ ] ILinkService 检测 `语音回复：` / `语音回复:` 前缀：
  - 去掉前缀 → 调用 commandHandler.handle(text) 生成回答
  - 调用 sendVoiceReply(reply) 合成语音并发送
  - 无前缀的文本回复正常走文本

**验收标准：**
- [ ] 发送"语音回复：请介绍一下Spring Boot"后收到语音消息
- [ ] 发送"语音回复：你好"后收到可播放的 WAV 语音
- [ ] 长文本被正确分段，每段独立发送语音

---

### 工单 13 — 视频理解

| 项目 | 说明 |
|------|------|
| **目标** | 接收微信视频消息，调用 LLM 分析视频内容 |
| **范围** | ai/VideoUnderstandingService.java、ILinkService 视频分支 |
| **分支** | `stage-13-video` |

**具体任务：**
- [ ] `application.properties` 配置：
  - `llm.video-model=qwen3-omni-flash`
  - `llm.video-api-url=https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- [ ] 创建 `ai/VideoUnderstandingService.java`：
  - `analyze(history, prompt, videoBytes, mimeType)` 方法
  - 视频 base64 编码（限制 10MB）
  - 支持流式响应解析和 JSON 响应解析
- [ ] ILinkService：
  - `client.downloadVideoFromMessageItem(videoItem)` 下载视频
  - MIME 检测（MP4: ftyp, WebM: 1a45dfa3）
  - 限制大小 7MB
  - 回复视频分析结果文本

**验收标准：**
- [ ] 发送小视频后收到文字分析
- [ ] 视频太大（>7MB）时返回友好提示

---

## 阶段 5：外部接口

### 工单 14 — REST API

| 项目 | 说明 |
|------|------|
| **目标** | 实现完整 REST API 用于测试和管理 |
| **范围** | controller/MessageController.java |
| **分支** | `stage-14-rest-api` |

**具体任务：**
- [ ] 创建 `controller/MessageController.java`（`@RestController`，`@RequestMapping("/api")`）
- [ ] 端点列表：
  - `GET /api/ping` — 健康检查
  - `GET /api/command?cmd=/help` — 执行命令
  - `GET /api/message/send?to=xxx&text=xxx` — 发送文本
  - `GET /api/message/sendVoiceReply?to=xxx&text=xxx` — 发送语音回复
  - `POST /api/message/sendImage` — 上传图片发送（含 `GET` HTML 表单）
  - `POST /api/message/sendVoice` — 上传语音发送（含 `GET` HTML 表单）
  - `POST /api/speech/transcribe` — 语音识别
  - `GET /api/message/botId` — Bot 状态
  - `GET /api/sessions/count` — 会话数
  - `DELETE /api/sessions/{userId}` — 清除会话
- [ ] 所有端点做参数校验和异常处理

**验收标准：**
- [ ] 浏览器访问 `GET /api/message/sendImage` 显示上传表单
- [ ] 浏览器访问 `GET /api/message/sendVoice` 显示上传表单
- [ ] 各端点正常返回（Bot 未登录时 send 系列返回 200，静默失败）

---

### 工单 15 — CLI 交互

| 项目 | 说明 |
|------|------|
| **目标** | 实现控制台交互循环，支持命令和语音识别 |
| **范围** | cli/CliRunner.java |
| **分支** | `stage-15-cli` |

**具体任务：**
- [ ] 创建 `cli/CliRunner.java`（`@Component @Profile("!test")`，实现 `CommandLineRunner`）
- [ ] 交互循环：
  - 打印 `> ` 提示符
  - 输入直接调用 `commandManager.dispatch()` 执行命令
  - `/exit` 或 `exit` 退出程序
- [ ] 支持：
  - `/asr <音频文件路径>` — 语音识别
  - `/asr-chat <音频文件路径>` — 语音识别 + LLM 对话
- [ ] 自动检测 Windows 控制台编码（GBK vs UTF-8）

**验收标准：**
- [ ] 启动后显示 `> ` 提示符
- [ ] 输入 `/help` 显示帮助信息
- [ ] 输入 `/asr test.wav` 显示识别文本
- [ ] 输入 `/exit` 退出程序

---

## 阶段 6：配置与优化

### 工单 16 — 语音场景适配

| 项目 | 说明 |
|------|------|
| **目标** | 语音回复模式下使用不同的系统提示词，让回答更简短口语化 |
| **范围** | ai/LLMService.java、application.properties |
| **分支** | `stage-16-voice-adaptation` |

**具体任务：**
- [ ] LLMService 新增 `voiceReply` 标志位：
  - `chat(history, newMessage, voiceReply)` 重载
- [ ] 新增配置（不冲突命名）：
  - `llm.voice-model=qwen-plus` — 语音场景使用的文本模型
  - `llm.voice-system-prompt=你是一个语音助手，请用简短口语化的方式回答...`
- [ ] CommandHandler 新增 `handle(text, fromUserId, voiceReply)` 重载
- [ ] ILinkService 在检测到 `语音回复：` 前缀时传 `voiceReply=true`

**验收标准：**
- [ ] 语音回复的回答比文本回复更简短
- [ ] 不影响普通文本对话的回答风格

---

### 工单 17 — 测试套件

| 项目 | 说明 |
|------|------|
| **目标** | 覆盖全部核心功能的测试 |
| **范围** | 全部测试类 |
| **分支** | `stage-17-testing` |

**具体任务：**
- [ ] `SessionManagerTest`：会话管理单元测试（20 轮上限、隔离、清除）
- [ ] `SpeechSynthesisServiceTest`：WAV 解析器测试
- [ ] `Demo1ApplicationTests`：
  - 上下文加载测试
  - 多模态图片对话端到端测试（含多轮记忆验证）
  - 图片发送边界测试（null、空、大文件）
  - 语音发送边界测试（null、空、大文件）
  - 会话管理功能测试
  - 内置测试图片生成器（200×150 PNG）
  - 内置测试 WAV 生成器（16kHz 1s 正弦波）
- [ ] 测试类统一用 `@SpringBootTest @ActiveProfiles("test")`
- [ ] `application-test.properties` 用 mock 配置，不调用真实 API

**验收标准：**
- [ ] `mvnw test` 全部通过

---

## 工单依赖图

```
工单 1 (骨架)
  │
  ├─── 工单 2 (LLM 对话) ─── 工单 3 (会话记忆) ─── 工单 4 (消息路由)
  │                                                            │
  │                          ┌──────────────────────────────────┘
  │                          ▼
  │              工单 5 (命令框架) ─── 工单 6 (内置命令)
  │
  └─── 工单 7 (iLink 登录) ─── 工单 8 (文本消息处理)
           │
           ├─── 工单 9 (图片消息) ─── 工单 10 (图片生成)
           ├─── 工单 11 (语音 ASR)
           ├─── 工单 12 (语音 TTS)
           └─── 工单 13 (视频理解)
           │
           ├─── 工单 14 (REST API)
           └─── 工单 15 (CLI)
           │
           └─── 工单 16 (语音适配)
           │
           └─── 工单 17 (测试套件)
```

## 说明

- **阶段 1~3** 可以不依赖 iLink SDK，纯 Spring + HTTP 开发，可用 CLI 测试
- **阶段 4** 开始需要 iLink SDK（本地 JAR），需要扫码登录微信
- **工单 16** 可以在阶段 4 之后随时插入
- **工单 17** 可以并行开发，每个工单完成后补充对应测试
