# 02 — LLM 多模态图片理解

**要构建的内容：** LLM 能够"看懂"用户发来的图片。`LLMService` 新增多模态重载方法，将图片以 base64 编码嵌入 OpenAI vision 格式的消息体，调用 qwen-plus 后返回对图片内容的文字描述。对话历史中图片用占位文本表示，不重复携带 base64 数据。

**阻塞于：** 01 — 接收图片消息

**状态：** ready-for-agent

- [ ] `LLMService.chatWithImage(history, systemPrompt, userText, imageBytes, imageMimeType)` 新重载方法
- [ ] 构建 OpenAI vision 兼容的多模态消息格式：user 消息的 `content` 为 `[{type:"text", text:...}, {type:"image_url", image_url:{url:"data:image/...;base64,..."}}]`
- [ ] 无图片时的纯文本消息保持原有 `"content": "文本"` 字符串格式，不改动
- [ ] `SessionManager.Message` 新增可选字段 `imageBase64` / `imageMimeType`，会话历史中图片记为 `[用户发送了一张图片]` 占位文本
- [ ] `CommandHandler.handle()` 新增重载接受图片参数，路由逻辑：/ 命令 → CommandManager；否则 → LLMService.chatWithImage
- [ ] 图片大小超限时的保护（建议 10MB 上限，超出返回友好提示）
- [ ] 验证：微信发一张日常生活照片，Clawbot 回复对图片内容的准确描述
- [ ] 验证：发图片后追问"刚才的图里有什么？"，Clawbot 能结合上下文回答（多轮记忆）
- [ ] 验证：纯文本对话不受影响，`/help` 命令仍然正常工作
