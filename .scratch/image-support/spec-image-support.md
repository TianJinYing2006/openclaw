# Image Support — 图片消息收发与理解

> Status: `ready-for-agent`

## Problem Statement

Clawbot 目前只处理文本消息。当微信用户发送图片时，`ILinkService.handleIncomingMessage()` 只检查 `text_item`，图片消息被静默忽略。同样，Clawbot 无法主动发送图片给用户。

ILink SDK 已经提供了完整的图片收发能力（`ImageItem`、`downloadImageFromMessageItem`、`sendImage`），qwen-plus 也支持多模态图片理解。缺失的是将它们串联起来的应用层代码。

## Solution

分三层补齐：

1. **接收层** — `ILinkService.handleIncomingMessage()` 识别 `image_item`，下载图片字节，传给消息处理管线
2. **理解层** — `LLMService` 新增多模态重载，将图片以 base64 编码嵌入 OpenAI 兼容的 vision 消息格式，qwen-plus 能"看懂"图片内容
3. **发送层** — 将已有的 `ILinkService.sendImage()` 接入回复流程，新增 REST 端点供运维/调试发送图片

## User Stories

1. 作为微信用户，我给公众号发一张图片，Clawbot 能识别图片内容并用文字回复我（例如"这是一只橘猫"）
2. 作为微信用户，我发送"图片+文字"（如"这张图里有什么？"），Clawbot 结合图片和文字给出回答
3. 作为微信用户，图片和文字的对话上下文保持连贯——我可以先发图片，再追问"刚才那张图里的颜色是什么？"，Clawbot 能理解我指的是哪张图
4. 作为运维人员，我可以通过 REST API 向指定用户发送图片
5. 作为开发者，CLI 中 `/help` 等命令不受影响，图片识别与命令系统互不干扰
6. 作为微信用户，收到图片回复时附有文字说明（caption），体验完整

## Implementation Decisions

1. **ImageItem 识别** — 在 `handleIncomingMessage` 的 `item_list` 遍历中，新增 `image_item` 分支。检测到 `image_item` 后调用 `client.downloadImageFromMessageItem(item)` 获取图片字节。如有 `text_item` 同时存在（图文混排），将文字作为用户对图片的描述一并传递。

2. **LLM 多模态消息格式** — qwen-plus 兼容 OpenAI vision 格式。新增 `LLMService.chatWithImage(history, systemPrompt, userText, imageBytes, mimeType)` 重载，构建 `messages` 数组时 user 消息的 `content` 变为数组：
   ```json
   {
     "role": "user",
     "content": [
       {"type": "text", "text": "这张图里有什么？"},
       {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
     ]
   }
   ```
   纯文本消息保持原有格式不变。

3. **会话历史中的图片** — Session 的 `Message` 记录新增可选字段 `imageBase64` 和 `imageMimeType`。图片不参与完整的 base64 历史回放（会导致 token 爆炸），而是记录缩略描述：在追加到历史时，图片消息用一段占位文本表示（如 `[用户发送了一张图片]`），仅当轮对话把实际图片数据传给 LLM。

4. **CommandHandler 集成** — `handle()` 方法新增重载 `handle(text, fromUserId, imageBytes, imageMimeType)`。图片消息同样先检查命令匹配（图片+`/`文本走命令），否则调用 `LLMService.chatWithImage()`。

5. **发送图片接入** — `ILinkService.sendImage()` 已经封装完成。在 `CommandHandler` 中，如果 LLM 回复不需要特殊处理（LLM 只返回文本描述，不会返回图片），则发送层仅需开放 REST 端点供外部调用。后续如需"LLM 生成图片"能力，可在此基础上扩展。

6. **REST 端点** — 新增 `POST /api/message/sendImage`，接受 `to`、`caption` 参数和 multipart file 上传。

7. **CLI 限制** — CLI 不支持图片输入（终端无图片粘贴能力），但 CLI 用户可以通过输入文本触发图片相关流程不受影响。

## Testing Decisions

- **What makes a good test:** 验证图片字节被正确接收并传给 LLM、多模态消息格式符合 OpenAI vision 规范、图文混排场景下文字不被丢弃
- **Seam:** `LLMService.chatWithImage()` 是核心新接缝，单独单元测试其消息构建逻辑（不实际调用 API）
- **Integration:** 端到端测试模拟微信图片消息到达 → 下载 → LLM 调用 → 文本回复的完整链路

## Out of Scope

- 图片生成（DALL·E / Stable Diffusion 集成）
- 图片存储持久化（本地文件或 OSS）
- GIF / 动图特殊处理
- 图片内容审核 / 安全过滤
- 语音、视频、文件等其他媒体类型（它们已有 SDK 支撑，模式与图片相同，后续可按此模式扩展）
