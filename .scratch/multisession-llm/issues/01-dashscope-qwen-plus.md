# 01 — Switch LLM backend to DashScope qwen-plus

**What to build:** Update the LLM connection so the Clawbot talks to Alibaba Cloud Bailian's DashScope API (`qwen-plus`) instead of the current default mimo endpoint. After this change, typing any non-command text into the CLI produces a reply from qwen-plus.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `application.properties` defaults changed: `llm.api-url` = `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`, `llm.model` = `qwen-plus`
- [ ] `llm.api-key` set to the Bailian API key
- [ ] Remove the old system prompt placeholder if no longer relevant; set a minimal Bailian-appropriate system prompt that can be overridden in config
- [ ] Verify from the CLI: type a free-text query, get a sensible qwen-plus reply
