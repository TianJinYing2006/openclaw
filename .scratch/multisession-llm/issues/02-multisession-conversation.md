# 02 — 多轮对话与会话记忆

**要构建的内容：** Clawbot 能记住用户之前说过的话。当同一个微信用户（或 CLI 用户）连续发送消息时，每次 LLM 调用都会包含最近 20 轮对话历史，让机器人在多轮对话中保持连贯。以 `/` 开头的命令不记入历史，完全绕过 LLM。

**阻塞于：** 01 — 切换 LLM 后端到百炼 DashScope qwen-plus

**状态：** ready-for-agent

- [ ] `SessionManager` 服务：`ConcurrentHashMap<String, Session>` 按用户存储 `Deque<Message>`，上限 20 轮
- [ ] `LLMService.chat(List<Message> history, String newMessage)` — 接受历史对话的重载方法，构建 DashScope 消息数组
- [ ] `CommandHandler.handle()` 将 `/` 开头的文本路由给 `CommandManager`（不修改历史），其余消息通过 `SessionManager` → `LLMService`（用户消息 + 回复追加到会话）
- [ ] `CliRunner` 统一经由 `CommandHandler.handle(text, "cli")` 路由，CLI 用户享有与微信通道一致的命令 + LLM 行为
- [ ] 验证：连续 CLI 输入可以相互衔接（例如："我叫小明" → "我叫什么名字？" 回复 "小明"）
- [ ] 验证：`/help` 等命令仍然正常工作，且不会被添加到 LLM 历史中
- [ ] 现有单条消息的 `LLMService.chat(String)` 仍然可用（向后兼容）
