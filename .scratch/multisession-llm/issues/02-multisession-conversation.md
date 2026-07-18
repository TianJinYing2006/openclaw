# 02 — Multi-turn conversation with session memory

**What to build:** The Clawbot remembers what a user said earlier. When the same WeChat user (or the CLI user) sends successive messages, the last 20 rounds of history are included in every LLM call so the bot stays coherent across turns. Commands (`/`-prefixed) are excluded from history and bypass the LLM entirely.

**Blocked by:** 01 — Switch LLM backend to DashScope qwen-plus

**Status:** ready-for-agent

- [ ] `SessionManager` service: `ConcurrentHashMap<String, Session>` storing per-user `Deque<Message>` with 20-round cap
- [ ] `LLMService.chat(List<Message> history, String newMessage)` — overload accepting prior conversation, builds DashScope message array
- [ ] `CommandHandler.handle()` routes `/`-prefixed text to `CommandManager` (no history mutation), routes everything else through `SessionManager` → `LLMService` (user message + reply appended to session)
- [ ] `CliRunner` routes through the same `CommandHandler.handle(text, "cli")` path, so the CLI user gets consistent command + LLM behaviour across turns
- [ ] Verified: successive CLI inputs build on each other (e.g. "My name is Xiao Ming" → "What's my name?" yields "Xiao Ming")
- [ ] Verified: `/help` and other commands still work and are NOT added to the LLM history
- [ ] Existing single-message `LLMService.chat(String)` still works (backward compatible)
