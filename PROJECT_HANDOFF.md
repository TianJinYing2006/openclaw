# YKD Summer Project Handoff

Last updated: 2026-07-31

This file is the durable handoff for future Codex tasks. It intentionally contains no API keys, access tokens, passwords, QR login data, or other secrets.

## How To Continue In A New Task

Open the same project folder `D:\YKD-summer`, then begin the new task with:

```text
Read PROJECT_HANDOFF.md first. Continue from the current project state and do not discard existing uncommitted work.
```

The file preserves project context, but it does not replace reading the relevant source files or the active runtime log for the task being performed.

## 2026-07-31 Current Override

This section is newer than historical status later in this file and takes precedence when the two conflict.

- Active feature branch/worktree: `codex/fashion-reference-sample-pipeline` in
  `C:\Users\14987\.codex\worktrees\0c35\YKD-summer`; do not touch `.codex-remote-attachments/`.
- The WeChat Agent allowlist has been reduced from 81 to 35 tools. Feishu, finance, entertainment, generic web/document
  tools and `search_fashion_products` are not exposed. The 35th tool is the aggregate
  `recommend_outfits_from_wardrobe`.
- The verified public sample contains 12 Looks and 24 TOP/BOTTOM cutouts. MySQL/OSS import and Qdrant indexing completed:
  `REFERENCE_LOOK=12`, `REFERENCE_GARMENT=24`. Do not import the full 230 Looks yet.
- V16 stores public Garment cutout assets. V17 adds durable outfit recommendation runs/options/items.
- The evidence-based recommendation chain now has code for user-scoped anchors, batch Look hydration, deterministic
  evidence scoring, duplicate removal, concrete missing-item analysis, persistent rank mapping, asynchronous no-person
  outfit rendering, real-cutout fallback and iLink completion push.
- Public Looks remain evidence only. Every returned outfit item must be an active item owned by the current user.
- Qdrant failure falls back to MySQL structured candidates and lowers evidence confidence. Image-provider failure falls
  back to a deterministic real-pixel outfit board.
- `mvn -q -DskipTests compile`, the targeted recommendation/render/tool/context/iLink tests, and the full
  `mvn -q test` suite have passed after tool integration. Test logs intentionally include Qdrant and image-provider
  failure cases because those tests verify the MySQL and real-cutout fallback paths.
- The explicit `PERSISTENCE_INTEGRATION=true` MySQL/Flyway verification for V17 is still pending. It needs a local
  database credential environment and must not be reported as complete until it has run successfully.
- Detailed current architecture is in `docs/features/FASHION_REFERENCE_LIBRARY.md`; implementation tracking is in
  `task_plan.md`, `findings.md`, and `progress.md`.

## Project Baseline

- Main project directory: `D:\YKD-summer`
- Active branch: `master`
- Stack: Java 21, Spring Boot 3.5, Spring AI, MySQL, Redis, Alibaba OSS, iLink / WeChat SDK
- User channels: WeChat/iLink bot; embedded Spring MVC/Thymeleaf management site
- Ports: `8080` and `8081` are served by the same Java process. Do not treat them as two independently restartable applications.
- Runtime entry point: `com.example.ykdsummer.YkdSummerApplication`
- iLink startup argument: `--ilink.enabled=true`

## Current Architecture

```text
WeChat user
  -> iLink SDK message polling
  -> managed bot instance / user scope
  -> message parsing and asset persistence
  -> session/history resolution
  -> Spring AI Chat Completions gateway
  -> tool calling and optional asynchronous work
  -> iLink reply or later task-completion push

Persistent services
  MySQL: users, managed instances, chat/business records, fashion data, reminder state
  Redis: short-lived session/cache/coordination support
  OSS: images and files
```

## Completed Work

### Platform And Operations

- Managed iLink bot instances support multiple isolated users/bot instances.
- MySQL and Redis are connected locally; Flyway manages schema migrations.
- OSS is used for file/image assets where configured.
- Admin site is served by the same Spring Boot process on port `8081`.
- Console logs were improved to display anonymized chat user, bot instance, model protocol, actual model, duration, tool calls and results.
- Long-running image work is asynchronous and reports a task ID; completed images are pushed back to the WeChat user.

### Agent And Tooling

- Spring AI Chat Completions is the active LLM integration path.
- The actual Agent loop is Spring AI's tool-calling recursion, not the old `AgentContext` data holder.
- A guard now limits a single user request to **4 tool-planning rounds**. A round is one model response requesting one or more tools; the number of tools inside a round is not limited.
- Configuration: `AI_MAX_AGENT_ROUNDS`, default `4`.
- On a fifth tool-planning round, the application returns a user-friendly stop message before running more tools.
- Feishu tool beans are offline by default. Restore only when needed with `FEISHU_TOOLS_ENABLED=true` or `app.feishu.tools.enabled=true` and restart.

### WeChat Reminders

- WeChat proactive reminders are implemented in `src/main/java/com/example/ykdsummer/reminder`.
- Agent tools:
  - `get_current_china_time`
  - `create_scheduled_agent_task`
  - `list_wechat_reminders`
  - `cancel_wechat_reminder`
- All new schedules use `create_scheduled_agent_task`, including simple reminders. At the due time the Agent decides
  whether to send a direct reminder or use other tools to finish the user's task.
- Reminder data is durable in MySQL through `V7__wechat_reminders.sql`; `V9__scheduled_agent_tasks.sql` adds the
  Agent intent fields; `V11__all_reminders_use_agent_execution.sql` upgrades unsent legacy reminders to Agent mode.
- Model: `ONCE`, `DAILY`, `WEEKLY` plus local time and `Asia/Shanghai` time zone. This is not a stored Cron-expression model.
- A Spring `@Scheduled` worker scans every 15 seconds by default, then uses a separate worker pool to proactively send iLink messages.
- At the due time, the persisted task intent re-enters the normal `AiChatService` and tool-calling chain. It can decide
  to use weather/web/map/image/file/time tools and proactively sends text, image, audio, document, or an automatic TXT
  file for a long result.
- Scheduled Agent executions do **not** receive reminder-management tools, so they cannot recursively create, list, or
  cancel schedules. The separate time tool remains available. Same-user tasks preserve conversation ordering; different
  users still execute in parallel.
- Delivery status supports retry, missing-context retry, managed-instance routing, and startup recovery. On this
  single-node deployment, deliveries left in `PROCESSING` by a restart are immediately returned to `RETRY`; overdue
  schedules are materialized and dispatched after the application is ready.
- Logs show each claimed delivery's ID, reminder ID, effective execution mode, task-prompt presence, and anonymized
  user hash. This makes a mode downgrade visible without logging user content.
- Relevant configuration begins with `app.reminder.*` in `src/main/resources/application.properties`.

### Fashion Agent Phase 1: User-Visible Wardrobe Intake

- Flyway migration: `V6__fashion_core.sql`.
- Core data and services are under `src/main/java/com/example/ykdsummer/fashion`.
- Core profile/wardrobe tools:
  - `get_fashion_profile`
  - `search_wardrobe`: AND filtering by category, primary/secondary color, style, fit, pattern, season, occasion and material.
    Common Chinese labels are normalized; broad categories such as outerwear, pants and shoes include their saved child categories.
  - `add_wardrobe_item` for explicitly labelled manual records only
- Photo workflow tools:
  - `analyze_wardrobe_photo`
  - `list_wardrobe_photo_candidates`
  - `update_wardrobe_candidate_labels`
  - `submit_garment_cutout`
  - `cancel_wardrobe_candidate`
  - `retry_garment_cutout`
  - `list_garment_draft_versions`
  - `preview_garment_draft_version`
  - `edit_garment_draft`
  - `confirm_wardrobe_candidate`
- Person-template tools:
  - `save_person_tryon_template`
  - `list_person_tryon_templates`
  - `select_person_tryon_template`
- Flow: uploaded image -> structured candidate drafts -> user review/edit -> explicit cutout selection -> async cutout
  -> WeChat image push -> optional draft-version comparison/revision -> explicit final confirmation -> durable wardrobe item.
  Every successful cutout or visual edit is retained as a selectable version of the same candidate; confirming a specific
  version stores that exact image in the wardrobe. Unconfirmed drafts expire after 30 minutes of inactivity.
- Every Agent turn rehydrates the current user's active candidate state from MySQL through
  `FashionAgentWorkflowContextProvider`. The LLM interprets confirmation/rejection/modification semantics, while tools
  validate the real candidate ID, owner, state and idempotency. Internal IDs are not written to conversation history or
  shown to WeChat users. The older keyword command handler now runs only as a conservative model-failure fallback.
- Empty internal IDs are accepted only when the relevant candidate is unique. Multiple pending candidates cause one
  clarification question instead of a guessed submission. Cancellation rejects the candidate, cancels pending/processing
  tasks, and prevents a late provider result from being stored or pushed.
- `retry_garment_cutout` is for outline/background recovery from the original photo. `edit_garment_draft` uses an existing
  isolated garment draft as the reference for visual changes such as length, color, or display styling; it never overwrites
  the earlier versions. Version display order is the stable submission/attempt order, not provider completion timestamp.
- Wardrobe vision prompt version `fashion-wardrobe-v2` accepts garments worn on a person when category, main color and
  major silhouette are visible. Small overlap by hands, bags, other garments, or a top covering a trouser waistband is
  allowed; an item that is almost entirely hidden, too blurred, or lacks a recognizable category still requires a retake.
- Before another visual request, wardrobe intake reuses a detailed visual summary already stored with the private OSS image.
  This avoids sending identical bytes through the vision provider twice. New images with no usable summary use a dedicated
  vision path: maximum 60 seconds, compacted to a 1280px longest edge when needed, low image detail, a 512-token output
  budget, and no inherited chat reasoning effort by default. Configure this through `app.ai.vision-*` / `AI_VISION_*`.
- A local opt-in OSS history probe (`FASHION_OSS_HISTORY_LIVE_TEST=true`) successfully read an existing private upload and
  produced worn-garment candidates in 9ms from its saved visual summary. The probe does not write a wardrobe item or
  modify the original asset.
- `V12__fashion_person_templates.sql` adds the private person-template table. It stores only source ownership and
  suitability (not identity, body measurements, or other biometric inferences). Exactly one active template is kept per user.
- All photo/template data is scoped to the managed iLink user; cross-user access is blocked at the repository boundary.
- A prior v1 public vision probe (`FASHION_VISION_LIVE_TEST=true`) completed with `qwen3.7-plus` in about 22-24 seconds.
  Its expectation has been updated for v2: a partly hidden hem may remain `READY` when the garment is still identifiable.
- Asynchronous single-wardrobe-item virtual try-on is implemented behind `FashionVirtualTryOnService` with durable tasks
  and WeChat completion push. Outfit recommendation and multi-item outfit collage/composition remain subsequent stages.

### Fashion Product Catalog Phase 2 In Progress

- `V8__fashion_product_catalog.sql` defines platform-owned `fashion_products` and `fashion_product_images`.
- The first catalog contains unisex basics and is administrator-maintained; no third-party commerce API is connected yet.
- `search_fashion_products` is a read-only Agent tool. It searches only `ACTIVE` catalog products and returns structured candidates, not invented products.
- The local admin site has a product catalog page at `/admin/catalog`. Reusing a product code updates that product.

## Required Restart Check

Source changes do not update an already running JVM. After a code change:

1. Stop the existing `YkdSummerApplication` process.
2. Build/rebuild the project in IntelliJ IDEA.
3. Start `YkdSummerApplication` once with `--ilink.enabled=true`.
4. Verify startup logs contain all of the following:
   - Flyway schema version `17` or later.
   - No `feishu_*` tools by default.
   - Reminder tools including `get_current_china_time` and `create_scheduled_agent_task`.
   - Fashion aggregate tool `recommend_outfits_from_wardrobe`.
   - The configured `app.ai.max-agent-rounds=4` value.

Important incident on 2026-07-28: PID `26188`, launched at 11:06, was still running code from before reminder and Feishu-offline changes. Its tool registry had Feishu tools and no reminder tools. Restarting the correct Java application is required before testing these additions.

## Verification Status

Latest full regression command:

```powershell
mvn -q '-Dopenai.image.base-url=https://api.lk888.ai/v1' test
```

The default suite skips `37` explicit opt-in live/integration tests as intended. The image base URL override is an existing test-environment
expectation; it is not a production configuration instruction.

Historical validation before the 2026-07-31 recommendation changes had `282` tests, `0` failures and `0` errors (`41` explicit live/integration probes skipped by
default). The opt-in MySQL workflow suite
also passed candidate restart recovery, user isolation, duplicate submission rejection, cancellation idempotency and
expiry filtering. Phase 3B validation reached Flyway schema `v15`; the tool registry contains `81` tools, including
`search_wardrobe`, `search_wardrobe_semantic`, `show_wardrobe_items` and `search_fashion_references`, with no duplicate names.

## Current Working Tree Notes

There is intentional, uncommitted work. Do not revert or discard it.

- Fashion Phase 1 photo intake/template files and migrations V6, V10 and V12 are uncommitted.
- Reminder implementation and migrations V7, V9, and V11 are uncommitted.
- Feishu default-offline changes are uncommitted.
- Agent 4-round guard changes are uncommitted.
- `.gitignore` was adjusted so the source package `src/main/java/com/example/ykdsummer/admin/ilink/` is no longer accidentally hidden by a broad ignore rule.

Before commit/push, inspect the worktree carefully and include only intended changes. Never commit secrets from local configuration files.

### Fashion Semantic Wardrobe Retrieval

- Local Qdrant is defined in `infra/qdrant/compose.yml`, fixed at server `v1.14.1` for compatibility with Spring AI 1.1.8's Qdrant Java client.
- The local profile enables `app.fashion.semantic.enabled=true` and reuses the existing Bailian credential through `app.tts.api-key`; no secret is duplicated in source.
- `text-embedding-v4` with 1024 dimensions is used for short Chinese wardrobe attribute text. `qwen3-rerank` is not used because it is a reranker rather than an embedding model.
- Migration `V14__fashion_semantic_index_jobs.sql` adds a durable asynchronous indexing outbox and backfills existing active wardrobe items.
- Confirmed wardrobe writes enqueue indexing in the same MySQL transaction. A dedicated bounded worker calls Bailian and Qdrant after application readiness, so WeChat confirmation is not blocked and interrupted jobs resume after restart.
- `search_wardrobe_semantic` performs natural-language retrieval with mandatory `appUserId` filtering, reloads active item facts from MySQL, and accepts existing structured filters. Qdrant failure degrades to MySQL filtering.
- Live verification created a temporary wardrobe item, indexed it, retrieved it with a Chinese weather/interview intent, proved another user could not retrieve it, then removed all temporary data.

### Fashion Phase 3B: Unified Labels And Public References

- Migration `V15__fashion_reference_library.sql` extends confirmed wardrobe items with a natural display name,
  parent category, annotation schema version and full structured attribute JSON.
- Public non-commerce reference data uses `fashion_reference_looks` plus child `fashion_reference_garments`; it is not
  stored in `fashion_products` and intentionally has no price, stock or purchase URL.
- Both private and public embeddings share one Qdrant collection but carry mandatory scopes:
  `USER_WARDROBE` with `appUserId`, or `PUBLIC_REFERENCE`. Existing private points were queued for rebuilding.
- The public importer is disabled by default, validates annotation schema `1.0.0`, stores images in OSS, writes the
  Look/Garments and semantic Outbox in MySQL, is idempotent by reference code, and rejects duplicate image SHA-256 values.
- Three local annotated Looks from `D:\创意` were imported for live acceptance: 9 garments were stored, indexed with
  Bailian `text-embedding-v4`, and retrieved through a Chinese semantic query. Bulk directory import remains disabled.
- Fashion tools now have one registration point, `FashionAgentToolSet`, preventing a Spring bean from existing without
  being exposed to the actual Chat Completions Agent.
- Operational details are in `docs/features/FASHION_REFERENCE_LIBRARY.md`.
- Live Phase 3B probes separately verified MySQL v15, real OSS persistence, Bailian embeddings, Qdrant indexing,
  Chinese public-reference retrieval and private wardrobe cross-user isolation.

## Recommended Next Steps

1. Apply V17 and run the explicit `PERSISTENCE_INTEGRATION=true` MySQL/Flyway integration test.
2. Use the existing 12 Looks/24 Garments for one real end-to-end recommendation; do not import all 230 Looks.
3. Manually verify from WeChat: select a wardrobe item, receive 1-3 deterministic options, receive asynchronous boards,
   then refer to “第一套/第二套” after an application restart.
4. Keep RAGFlow, products, model training, feedback learning and
   automatic multi-item virtual try-on out of this phase.

## Target Fashion Architecture

```text
WeChat natural-language/image input
  -> identity/session + multimodal asset pipeline
  -> Fashion Agent orchestration
  -> profile / wardrobe / weather / product / recommendation / knowledge / try-on tools
  -> MySQL + Redis + OSS + future replaceable vector store
  -> final reply or asynchronous result push
```

Product recommendations must use structured filtering and candidate scoring first. RAG is for fashion knowledge, care instructions and style guidance; it should not be the sole mechanism for product recommendation.

## Do Not Do Yet

- Do not introduce microservices, multiple autonomous agents, a knowledge graph, or a vector database solely for appearance.
- Do not replace the existing iLink/WeChat integration or Spring AI chat chain.
- Do not use SQLite for the multi-user production-oriented data model.
- Do not expose any credential in source, logs, commits, documents or chat history.
