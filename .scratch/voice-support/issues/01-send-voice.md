# 01 — 发送语音消息

**要构建的内容：** 提供 REST 端点支持上传音频文件并通过微信发送语音消息。同时修复 missing 的图片 REST 端点。

**阻塞于：** 无

**状态：** ready-for-agent

- [ ] `ILinkService.sendVoiceWithTyping(toUserId, voiceBytes, fileName, playTimeMs, sampleRate)` — 先显示输入态再发送语音
- [ ] `MessageController` 新增 `POST /api/message/sendVoice` — multipart file + `to` + `caption` 参数
- [ ] `MessageController` 新增 `GET /api/message/sendVoice` — HTML 表单页面
- [ ] `MessageController` 恢复 `POST /api/message/sendImage` — 图片发送端点（之前丢失）
- [ ] `MessageController` 恢复 `GET /api/message/sendImage` — HTML 表单页面
- [ ] 10MB 文件大小校验
- [ ] 发送失败时的错误日志和 HTTP 错误响应
- [ ] 验证：通过 REST API 上传音频文件，微信端收到语音消息
- [ ] 验证：`GET /api/message/sendVoice` 返回 HTML 表单页面
