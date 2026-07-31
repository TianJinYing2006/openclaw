 # SQLite 持久化存储方案

 ## 设计原则

 1. **对齐 Spring AI 接口**：不另起炉灶，实现 `ChatMemory` 接口，保持生态兼容
 2. **最小化改动现有代码**：`AiChatService` 的编排逻辑（同步锁/budget/trace）不动，只替换存储后端
 3. **保持项目既有风格**：不引入 ORM，用 Spring JDBC + 手写 DAO，与 `FileStorageService` 风格一致
 4. **表结构精简**：两张核心表 + 一个配置表，不做软删除归档

 ---

 ## 存储层架构

 ```
 src/main/java/com/example/ykdsummer/storage/db/
 ├── DatabaseManager.java        -- DataSource 配置 + 建表初始化
 ├── SqliteChatMemory.java       -- 实现 ChatMemory 接口，会话+消息 CRUD
 ├── SqliteChatMemoryProperties.java -- 数据库路径、清理策略等配置
 └── CursorDao.java              -- iLink 消息游标持久化（可选，替代 .properties）
 ```

 ### 与现有模块的关系

 ```
 AiChatService                              ← 编排层不动
   ├── 替换: Caffeine → SqliteChatMemory    ← 实现 ChatMemory
   └── 保留: synchronized + budget + trace

 ILinkSessionStore                          ← SDK 会话层不动
   └── 可选: CursorDao 替代 .properties     ← 还是保留 .properties 更安全

 SpringAiChatCompletionsGateway             ← 网关层不动
   └── 仍然接收 List<ConversationMessage>   ← 调用方不变
 ```

 ---

 ## 数据库表设计

 ### 表 1: `chat_sessions` — 会话主表

 ```sql
 CREATE TABLE IF NOT EXISTS chat_sessions (
     user_id      TEXT    NOT NULL,
     chat_type    TEXT    NOT NULL DEFAULT 'single'
                          CHECK(chat_type IN ('single', 'group')),
     nick_name    TEXT    DEFAULT '',
     status       TEXT    NOT NULL DEFAULT 'active'
                          CHECK(status IN ('active', 'archived')),
     msg_count    INTEGER NOT NULL DEFAULT 0,
     window_size  INTEGER NOT NULL DEFAULT 20,
     created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
     updated_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
     PRIMARY KEY (user_id, chat_type)
 );
 ```

 **设计考量**：
 - 复合主键 `(user_id, chat_type)`：区分私聊和群聊，防止群 ID 与用户 ID 冲突
 - `window_size` 独立控制每个用户的上下文窗口
 - `status` 字段标记长时间不活跃的会话，不做逻辑隔离（见清理策略）

 ### 表 2: `chat_messages` — 消息明细表

 ```sql
 CREATE TABLE IF NOT EXISTS chat_messages (
     id              INTEGER PRIMARY KEY AUTOINCREMENT,
     user_id         TEXT    NOT NULL,
     chat_type       TEXT    NOT NULL DEFAULT 'single',
     role            TEXT    NOT NULL CHECK(role IN ('user','assistant','system')),
     content         TEXT    NOT NULL,
     msg_type        TEXT    DEFAULT 'text',
     wx_msg_id       TEXT    DEFAULT '',
     token_count     INTEGER DEFAULT 0,
     created_at      TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
     FOREIGN KEY (user_id, chat_type) REFERENCES chat_sessions(user_id, chat_type)
         ON DELETE CASCADE
 );
 CREATE INDEX IF NOT EXISTS idx_messages_lookup
     ON chat_messages(user_id, chat_type, created_at);
 ```

 **设计考量**：
 - `ON DELETE CASCADE`：删除会话时自动清理关联消息
 - `wx_msg_id` 预留用于微信消息去重（未来可对接 `RecentMessageIds`）
 - 按 `(user_id, chat_type, created_at)` 建索引，覆盖最常用的历史查询

 ### 表 3: `user_contexts` — 用户长期上下文（预留）

 ```sql
 CREATE TABLE IF NOT EXISTS user_contexts (
     user_id      TEXT NOT NULL,
     chat_type    TEXT NOT NULL DEFAULT 'single',
     context_key  TEXT NOT NULL,
     context_val  TEXT NOT NULL DEFAULT '',
     updated_at   TEXT NOT NULL DEFAULT (datetime('now','localtime')),
     PRIMARY KEY (user_id, chat_type, context_key)
 );
 ```

 **设计考量**：
 - 简单的 key-value 结构，供未来长期记忆功能使用
 - 与 `VoiceSettingsTools` 的语音偏好联动（如记忆用户选择的音色）

 ---

 ## 与 Spring AI ChatMemory 接口的映射

 ```java
 public interface ChatMemory {
     void add(String conversationId, List<Message> messages);
     List<Message> get(String conversationId, int lastN);
     void clear(String conversationId);
 }
 ```

 `SqliteChatMemory` 的实现映射：

 | ChatMemory 方法 | 对应 SQL 操作 |
 |----------------|--------------|
 | `add(id, messages)` | `INSERT INTO chat_messages`（批量） |
 | `get(id, lastN)` | `SELECT * FROM chat_messages WHERE user_id=? AND chat_type=? ORDER BY created_at DESC LIMIT lastN` |
 | `clear(id)` | `DELETE FROM chat_messages WHERE user_id=? AND chat_type=?` |

 **conversationId 编码约定**：
 - `conversationId` = `"{userId}::{chatType}"`，方便解析回复合主键
 - 例如：`"wx_user_abc::single"`

 ---

 ## 对 AiChatService 的改动

 ### 改动目标：最小侵入

 现有 `AiChatService` 内部结构：

 ```
 Cache<String, UserConversation> conversations
   ├── conversation.get(userId, k -> new UserConversation())
   ├── conversation.copyMessages()
   ├── conversation.remember(msg, maxMessages)
   └── conversation.invalidate(userId)
 ```

 改为：

 ```
 SqliteChatMemory chatMemory
   ├── chatMemory.get(conversationId, windowSize)
   ├── chatMemory.add(conversationId, messages)
   └── chatMemory.clear(conversationId)
 ```

 **保留不变的部分**：
 - `synchronized` 按用户串行化（改为 `Striped<ReadWriteLock>` 锁分段）
 - `AiRequestBudget` 预算控制
 - `AiTraceLogger` 追踪日志
 - `AiUsageMeter` 用量统计
 - `answerInternal()` 的整体编排流程

 **具体改动点**：

 ```diff
 - private final Cache<String, UserConversation> conversations;
 + private final SqliteChatMemory chatMemory;

 - UserConversation conversation = conversations.get(userId, ...);
 + String conversationId = userId + "::" + resolveChatType();
 + int windowSize = chatMemory.getWindowSize(conversationId);

 - List<ConversationMessage> history = conversation.copyMessages();
 + List<ConversationMessage> history = chatMemory.get(conversationId, windowSize);

 - conversation.remember(userMsg, maxMessages);
 + chatMemory.add(conversationId, List.of(toSpringMessage(userMsg)));

 - conversation.remember(assistantMsg, maxMessages);
 + chatMemory.add(conversationId, List.of(toSpringMessage(assistantMsg)));

 - conversations.invalidate(userId);
 + chatMemory.clear(conversationId);
 ```

 `AiChatService` 不再需要内部类 `UserConversation`，移除约 30 行。

 ### 并发安全

 现有代码用 `synchronized (conversation)` 锁住每个用户的 `UserConversation` 对象。
 改为 SQLite 后，同一用户的串行化通过应用层锁分段实现：

 ```java
 private final Striped<ReadWriteLock> userLocks = Striped.lazyWeakReadWriteLock(1024);

 // 在 answerInternal 中：
 ReadWriteLock lock = userLocks.get(userId);
 lock.writeLock().lock();
 try {
     // 读写 SQLite
 } finally {
     lock.writeLock().unlock();
 }
 ```

 SQLite 本身是单写连接，但应用层加锁能保证"读-改-写"原子性与原来一致。

 ---

 ## 对 ILinkSessionStore 的处理

 **结论：不做改动，保持 .properties 文件方案。**

 理由：
 1. iLink 登录凭证（token/accountId）属于密码级敏感信息，混入 SQLite 反而不利权限控制
 2. 当前 `.properties` + 原子写入 + POSIX 权限收束已经足够安全
 3. 游标写入频率低（每次 getupdates 批量确认），SQLite 不会带来性能收益

 ---

 ## 清理策略（精简为两层）

 ### 第一层：用户主动清理

 触发方式：用户说"重置对话"或 AI 调用 `clear_current_memory` Tool

 ```sql
 DELETE FROM chat_messages WHERE user_id = ? AND chat_type = ?;
 ```

 对应代码：`SqliteChatMemory.clear(conversationId)`

 ### 第二层：定时 TTL 清理

 触发方式：`@Scheduled` 定时任务，每日凌晨执行

 ```sql
 -- 删除超过 N 天未活跃会话的所有消息
 DELETE FROM chat_messages
 WHERE (user_id, chat_type) IN (
     SELECT user_id, chat_type FROM chat_sessions
     WHERE updated_at < datetime('now', '-30 days', 'localtime')
 );

 -- 删除超过 30 天未活跃的会话（消息已被级联删除）
 DELETE FROM chat_sessions
 WHERE updated_at < datetime('now', '-30 days', 'localtime');
 ```

 **不做软删除归档**：
 - SQLite 的 `VACUUM` 不回收软删除占用的页面
 - 个人微信 bot 的数据规模下，物理删除更简单

 ---

 ## 消息流时序（完整链路）

 ```
 微信消息 → ILinkBotService（去重/限流/线程池）
   → ILinkReplyService（消息类型路由）
     → AgentCoordinator.execute()
       → AiChatService.answer()
         ┌──────────────────────────────────────────────┐
         │ 1. userId + chatType → conversationId        │
         │ 2. chatMemory.get(conversationId, window)    │ ← 读历史
         │ 3. budget.plan(history, prompt)              │
         │ 4. gateway.generate(history, prompt, ...)    │ ← Spring AI
         │ 5. chatMemory.add(conversationId, userMsg)   │ ← 写用户消息
         │ 6. chatMemory.add(conversationId, aiReply)   │ ← 写 AI 回复
         └──────────────────────────────────────────────┘
       → Agent 结果分发
   → ILinkBotService 发送回复
 ```

 **关键保证**：
 - 步骤 2-6 在 `writeLock` 中执行，同一用户串行
 - 步骤 5-6 只有 model 成功返回后才写入（失败不污染历史）
 - `memoryText()` 逻辑不变（图片/文件不进历史）

 ---

 ## 依赖变更

 `pom.xml` 新增：

 ```xml
 <dependency>
     <groupId>org.xerial</groupId>
     <artifactId>sqlite-jdbc</artifactId>
     <version>3.47.1.0</version>
 </dependency>
 <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-jdbc</artifactId>
 </dependency>
 ```

 `application.properties` 新增：

 ```properties
 app.db.path=${APP_DB_PATH:.db/wechat_bot.db}
 app.db.cleanup-max-age=${APP_DB_CLEANUP_MAX_AGE:30d}
 app.db.cleanup-interval=${APP_DB_CLEANUP_INTERVAL:86400000}
 ```

 ---

 ## 实现优先级

 | 步骤 | 内容 | 估算 |
 |------|------|------|
 | 1 | 加 `sqlite-jdbc` 依赖 + `DatabaseManager`（DataSource+建表） | ~50 行 |
 | 2 | `SqliteChatMemory` 实现 `ChatMemory` 接口 | ~150 行 |
 | 3 | `SqliteChatMemoryProperties` 配置绑定 | ~40 行 |
 | 4 | 修改 `AiChatService`：替换 Caffeine 为 SqliteChatMemory | ~60 行改动 |
 | 5 | 定时清理服务 `DbCleanupService` | ~80 行 |
 | 6 | 单元测试（SqliteChatMemoryTest + 适配测试） | ~150 行 |

 合计：约 **500 行新增代码**，对现有文件的改动控制在 **2 个类**（`AiChatService` + `pom.xml`）。

 ---

 ## 不做的事（明确排除）

 | 事项 | 原因 |
 |------|------|
 | 消息归档/软删除 | SQLite 不擅长，物理删除更简单 |
 | 用户长期记忆/画像 | 表 `user_contexts` 已预留，本次不实现逻辑 |
 | 全文搜索 | SQLite FTS 扩展，后续如有搜聊天记录需求可加 |
 | 迁移 iLink 凭证到 SQLite | .properties 更安全，改动无收益 |
 | 分布式部署支持 | SQLite 是嵌入式数据库，不支持并发写 |
 | 覆盖 Spring AI Advisor 体系 | 现有网关用 buildPrompt() 手动构建，本次不改 |
