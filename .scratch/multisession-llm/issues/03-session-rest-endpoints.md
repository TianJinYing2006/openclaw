# 03 — 会话管理 REST 接口

**要构建的内容：** 供运维人员检视和清除进行中对话会话的 REST 端点，无需重启应用。

**阻塞于：** 02 — 多轮对话与会话记忆

**状态：** ready-for-agent

- [ ] `GET /api/sessions/count` 返回 `SessionManager` 中活跃的用户会话数量
- [ ] `DELETE /api/sessions/{userId}` 清除该用户的会话，成功返回 204，会话不存在返回 404
- [ ] 端点添加到现有的 `MessageController` 中
- [ ] 验证：清除会话后，该用户的下一条消息从头开始（无历史记录）
