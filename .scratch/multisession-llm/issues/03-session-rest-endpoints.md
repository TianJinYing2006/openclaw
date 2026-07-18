# 03 — Session inspection REST endpoints

**What to build:** REST endpoints that let an operator inspect and clear in-flight conversation sessions without restarting the application.

**Blocked by:** 02 — Multi-turn conversation with session memory

**Status:** ready-for-agent

- [ ] `GET /api/sessions/count` returns the number of active user sessions in `SessionManager`
- [ ] `DELETE /api/sessions/{userId}` clears the session for that user, returning 204 on success or 404 if no session existed
- [ ] Endpoints added to the existing `MessageController`
- [ ] Verified: after session cleared, the next message from that user starts fresh (no prior history)
