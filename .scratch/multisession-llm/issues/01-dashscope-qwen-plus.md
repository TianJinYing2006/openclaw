# 01 — 切换 LLM 后端到百炼 DashScope qwen-plus

**要构建的内容：** 将 LLM 连接切换到阿里云百炼 DashScope API（`qwen-plus`），替换当前默认的 mimo 端点。完成后，在 CLI 中输入任意非命令文本，都会收到 qwen-plus 的回复。

**阻塞于：** 无 — 可立即开始。

**状态：** ready-for-agent

- [ ] `application.properties` 默认值已更改：`llm.api-url` = `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，`llm.model` = `qwen-plus`
- [ ] `llm.api-key` 设置为百炼 API Key
- [ ] 移除旧的系统提示词占位；设置适合百炼的系统提示词，允许通过配置覆盖
- [ ] 验证：从 CLI 输入自由文本查询，收到 qwen-plus 的正常回复
