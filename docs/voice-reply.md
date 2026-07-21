# 语音回复功能说明

## 功能效果

项目现在支持两种语音回复方式：

1. 用户直接发送微信语音：项目先做语音识别，再调用大模型生成回答，最后把回答合成为语音发回去。
2. 用户发送文本 `语音回复：问题内容` 或 `语音回复:问题内容`：项目用普通文本聊天流程生成回答，再把回答合成为语音发回去。

## 回复模式判定

文本消息到达 `ILinkService.handleIncomingMessage()` 后，根据是否带 `语音回复：` 前缀走不同分支：

| 项目 | 说明 |
|------|------|
| **处理器** | `ILinkService.handleIncomingMessage()` |
| **判定条件** | `isVoiceReplyPrompt(text)` → `text.startsWith("语音回复：") \|\| text.startsWith("语音回复:")` |
| **分支 A（文本模式）** | 不以 `语音回复：` 开头 → `commandHandler.handle(text)` → `sendTextWithTyping(reply)` |
| **分支 B（语音模式）** | 以 `语音回复：` 开头 → 去掉前缀 → `commandHandler.handle(strippedText)` → `sendVoiceReply(reply)` |

也可以通过 HTTP 手动触发：

```text
GET /api/message/sendVoiceReply?to=userId&text=你好
```

## 技术流程

```text
用户语音
  ↓
iLink SDK 收到 VoiceItem
  ↓
优先读取微信自带转写 text
  ↓
没有转写时下载语音字节
  ↓
SpeechRecognitionService 调用 qwen3-asr-flash 识别
  ↓
CommandHandler 进入原有 LLM 对话流程
  ↓
LLMService 调用 qwen-plus 生成文本回答
  ↓
SpeechSynthesisService 调用 qwen3-tts-flash 合成语音
  ↓
下载/读取 TTS 返回音频
  ↓
解析 WAV，提取 PCM、采样率、时长
  ↓
iLink SDK sendVoice 发送语音消息
```

文本触发语音回复时，前半段不需要 ASR：

```text
语音回复：问题内容
  ↓
去掉“语音回复：”前缀
  ↓
CommandHandler + LLMService 生成文本回答
  ↓
SpeechSynthesisService 合成语音
  ↓
iLink SDK sendVoice 发送语音
```

## 用到的技术

| 功能 | 技术 |
|---|---|
| 微信消息接收/发送 | iLink SDK |
| 语音识别 | DashScope `qwen3-asr-flash` |
| 普通对话 | DashScope OpenAI Compatible Chat，模型 `qwen-plus` |
| 语音合成 | DashScope `qwen3-tts-flash` |
| HTTP 请求 | Java 17 `HttpClient` |
| JSON 构建/解析 | Jackson `ObjectMapper` / `JsonNode` / `ObjectNode` |
| 音频处理 | Java 字节数组解析 WAV，提取 PCM 数据 |
| 配置管理 | Spring `@Value` + `application.properties` |
| 服务装配 | Spring `@Service` + `@Autowired` |

## 为什么用这些技术

### 为什么用 DashScope TTS

项目前面已经使用 DashScope 做普通对话、图片识别、语音识别和视频理解，继续使用 DashScope 的 TTS 可以复用同一个 API Key 和 Java HTTP 调用方式，不需要再引入新的云厂商 SDK。

### 为什么用 Java HttpClient

项目原有 `LLMService`、`SpeechRecognitionService` 已经使用 Java 17 自带 `HttpClient`，继续使用它可以保持风格统一，而且不需要新增 OkHttp、Apache HttpClient 等额外依赖。

### 为什么用 Jackson

原项目已经使用 Jackson 构建和解析大模型 JSON。TTS 请求和响应也是 JSON，所以继续使用 `ObjectMapper`、`ObjectNode`、`JsonNode` 可以减少重复工具和学习成本。

### 为什么解析 WAV 成 PCM

DashScope TTS 常见返回是音频 URL 或 Base64 音频，音频通常是 WAV 封装。iLink SDK 发送语音时需要传入语音字节、播放时长、采样率、编码类型等信息。直接把 WAV 整个文件当语音体发送，微信端可能无法按语音消息正确播放，所以新增 `SpeechSynthesisService` 解析 WAV 头，提取真正的 PCM 音频数据，并计算语音时长。

### 为什么增加“语音回复：”前缀

如果普通文本全部都变成语音回复，会影响原来的文本聊天体验。所以文本消息默认仍然回复文本，只有用户明确发送 `语音回复：xxx` 时才返回语音。用户发来的微信语音则默认返回语音，更符合语音对话场景。

## 修改的核心文件

| 文件 | 作用 |
|---|---|
| `src/main/java/com/example/demo/service/SpeechSynthesisService.java` | 新增文本转语音服务，调用 TTS、下载音频、解析 WAV/PCM |
| `src/main/java/com/example/demo/service/ILinkService.java` | 接入语音回复流程，语音输入改为语音回答，支持 `语音回复：` 文本触发 |
| `src/main/java/com/example/demo/control/MessageController.java` | 新增 HTTP 测试入口 `/api/message/sendVoiceReply` |
| `src/main/resources/application.properties` | 新增 TTS 模型、音色、语言和分段长度配置 |

## 中间遇到的阻碍

1. `rg` 搜索工具在当前 Windows 环境中被拒绝执行，所以改用 PowerShell 的 `Get-ChildItem` 和 `Select-String` 查看项目结构与代码引用。
2. `apply_patch` 在当前环境中被系统拒绝执行，后续改用 PowerShell 在项目目录内受控写入文件。
3. 第一次编辑 `ILinkService.java` 时，因为原文件存在中文编码显示异常，整文件重写后触发了“未结束的字符串文字”编译错误。
4. 为解决编码问题，先从 Git 恢复 `ILinkService.java`，再重建一个 UTF-8 的完整版本，把图片、语音识别、视频识别和语音回复逻辑统一写回。
5. `mvnw.cmd test` 在当前环境中启动失败，提示 `Cannot index into a null array`，所以改用本机 `mvn test`。
6. 首次 `mvn test` 被沙箱网络限制拦截，后来申请联网权限后继续下载依赖并编译。

## 注意事项

- 语音回复依赖 `llm.api-key`，如果 API Key 错误或没有 TTS 模型权限，会合成失败。
- 当前 `sendVoiceReply` 在语音合成或发送失败时，会自动退回发送文本回复，避免用户完全收不到回答。
- `llm.tts-max-chars=400` 用于把长回复分段合成，避免单次 TTS 文本太长导致接口失败。