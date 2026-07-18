# Multisession LLM Conversation

> Status: `ready-for-agent`

## Problem Statement

The Clawbot currently handles every incoming message as a single-turn interaction: it either matches a command or passes the text to the LLM and replies. There is no memory of prior messages. A user who sends "My name is Xiao Ming" followed by "What's my name?" will get a blank stare — the LLM has no access to the earlier exchange.

A conversational bot must remember what was said. For this project, the fix is a session manager that keeps the last 20 message-pairs per user in memory, injects them into every LLM call, and lets the LLM carry on a coherent multi-turn conversation.

## Solution

Introduce a `SessionManager` backed by a `ConcurrentHashMap<userId, Session>`. When a message arrives:

1. Look up (or create) the session for the user.
2. If the message starts with `/`, dispatch it to the command system immediately (no session context consumed; no history mutated).
3. Otherwise, call `LLMService.chat(session.history + new message)`, append the user message and the assistant reply to the history, trim to 20 rounds, and reply via ILink.

The session history is held entirely in-process — lost on restart, acceptable for development.

## User Stories

1. As a WeChat user, I want the Clawbot to remember things I said earlier in the conversation, so that I can have a natural back-and-forth without repeating myself.
2. As a WeChat user, I want the Clawbot's memory capped at a reasonable limit, so that it stays responsive and I don't get bizarre completions from an overstuffed prompt.
3. As a developer running the CLI, I want to type queries directly into the console and get LLM replies, so I can test the session manager without sending WeChat messages.
4. As a WeChat user, I want `/help` and other commands to keep working exactly as before, without interfering with my chat history.
5. As an operator, I want sessions to be isolated per user, so that User A's conversation never leaks into User B's replies.
6. As an operator, I want a REST endpoint to inspect or clear sessions, so I can debug stuck or runaway conversations.

## Implementation Decisions

1. **SessionManager** — new Spring `@Service` holding a `ConcurrentHashMap<String, Session>`. Each `Session` wraps a `Deque<Message>` and a `createdAt` timestamp. Exposes `getOrCreate(userId)`, `append(userId, role, content)`, `clear(userId)`, and `getSessionCount()`.

2. **LLMService.chat(List<Message> history, String newUserMessage)** — overload the existing single-message `chat(String)` to accept a full message list. The `history` parameter is the last-N rounds extracted from the session. Internally it builds the OpenAI-compatible `messages` array and calls DashScope `/v1/chat/completions`.

3. **CommandHandler integration** — the `handle(text, fromUserId)` method becomes the single branching point: `/`-prefixed text hits `CommandManager.dispatch()` immediately and is **not** added to session history; everything else calls `LLMService.chat(history, text)` and records both sides.

4. **20-round cap** — implemented at appending time. After `session.append(assistantReply)`, if the deque size exceeds 40 entries (20 user + 20 assistant), remove the oldest two (one pair). The count includes the system prompt if one is used; exclude it from the cap calculation by keeping it as a separate field in `Session`.

5. **DashScope endpoint** — `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`. The existing `LLMService` already targets an OpenAI-compatible URL; the only change is switching the default URL and model name in `application.properties`.

6. **Session inspection endpoint** — `GET /api/sessions/count` and `DELETE /api/sessions/{userId}` added to `MessageController`, delegating to `SessionManager`.

7. **CLI integration** — `CliRunner` routes user input through the same `CommandHandler.handle(text, "cli")` path, so the CLI gets the same command + LLM behaviour as a WeChat user. The "cli" user has its own session in the `SessionManager`.

## Testing Decisions

- **What makes a good test:** Verify external behaviour — sessions accumulate messages, commands bypass the LLM, cap is enforced. Never assert on implementation details (ConcurrentHashMap key structure, internal deque ordering).
- **Seam:** `SessionManager` is the single new seam. Unit-test it in isolation with a simple `new SessionManager()`. Integration-test `CommandHandler.handle()` with mocked `LLMService` and real `SessionManager`.
- **Prior art:** `src/test/java/com/example/demo/Demo1ApplicationTests.java` (Spring Boot context test). Add `SessionManagerTest.java` as a plain unit test and `CommandHandlerTest.java` as a Spring integration test.

## Out of Scope

- Session persistence across restarts (Redis, database)
- Image receipt, analysis, or generation
- Multi-instance session sharing
- Session TTL / idle eviction
- Rate limiting per user
