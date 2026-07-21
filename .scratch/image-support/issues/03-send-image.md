# 03 — 发送图片消息

**要构建的内容：** 应用层可以通过回复流程向用户发送图片，并提供 REST 端点供运维调试使用。ILink SDK 的图片发送能力已封装在 `ILinkService.sendImage()` 中，本工单将其接入实际业务场景并暴露 HTTP 接口。

**阻塞于：** 02 — LLM 多模态图片理解

**状态：** ready-for-agent

- [ ] `ILinkService` 新增 `sendImageWithTyping(toUserId, imageBytes, fileName, caption, typingMs)` 方法，先显示输入态再发送图片
- [ ] `MessageController` 新增 `POST /api/message/sendImage` 端点，接受 multipart file + `to` + `caption` 参数
- [ ] 图片上传大小校验（建议 10MB 上限）
- [ ] 发送失败时的错误日志和 HTTP 错误响应
- [ ] 验证：通过 REST API 向指定用户发送图片，微信端正常收到图片和文字说明
- [ ] 验证：`GET /api/message/sendImage` 返回 HTML 表单页面，方便浏览器手动测试
