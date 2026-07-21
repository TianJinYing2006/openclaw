# 01 — 接收图片消息

**要构建的内容：** `ILinkService.handleIncomingMessage()` 能够识别微信用户发来的图片消息，下载图片数据，并在日志中输出图片信息。同时保留对纯文本消息的现有处理不变。如果图片附带文字（如"帮我看看这张图"），文字与图片一并传递给后续处理。

**阻塞于：** 无 — 可立即开始。

**状态：** ready-for-agent

- [ ] `handleIncomingMessage()` 的 `item_list` 遍历中新增 `image_item` 分支，检测 `MessageItem.getImage_item()`
- [ ] 调用 `client.downloadImageFromMessageItem(item)` 获取图片字节数据
- [ ] 日志记录：`fromUserId`、图片大小（bytes）、MIME 类型（从文件名后缀推断）
- [ ] 图片下载失败时的错误处理（记录日志，发送文本回复告知用户"图片接收失败"）
- [ ] 同一消息中 `text_item` 与 `image_item` 共存时，文字作为描述一并提取
- [ ] 验证：通过微信向公众号发图片，日志中可见图片接收记录，文本消息不受影响
