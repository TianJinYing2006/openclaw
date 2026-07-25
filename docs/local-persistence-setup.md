# 本地 MySQL、Redis 与 OSS 持久化搭建

本项目在单台服务器环境中使用以下分层：

| 组件 | 职责 | 是否保存业务事实 |
| --- | --- | --- |
| MySQL 8 | 聊天历史、图片任务、资产索引、审计与用量账本 | 是 |
| Redis | 缓存、限流、幂等、后续分布式任务协调 | 否 |
| OSS | 图片、文件、视频等二进制对象 | 是，保存对象内容 |

不要把 OSS 图片或文件的二进制内容写入 MySQL。数据库只保存资产 ID、版本、对象键、MIME 类型和描述元数据。

## 1. 前置条件

- JDK 21
- Maven 3.9+
- MySQL 8.0+
- Redis 7+，本项目当前可直接使用 `D:\redis-server.exe`
- 已配置的 OSS 凭证和 Bucket

MySQL 必须可从本机访问 `127.0.0.1:3306`。Redis 默认使用 `127.0.0.1:6379`，本地开发不设置密码。

## 2. 创建数据库

使用有建库权限的账号执行一次：

```sql
CREATE DATABASE IF NOT EXISTS ykd_summer
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

不要手工创建业务表。应用启动后 Flyway 会按 `src/main/resources/db/migration` 中的版本化 SQL 自动创建和升级表。

## 3. 本地私密配置

在项目根目录创建 `application-local.properties`，例如 `D:\YKD-summer\application-local.properties`。该外部配置的运行时优先级高于打包资源，并且已被 `.gitignore` 忽略；禁止提交真实密码、OSS AccessKey 或模型密钥。

```properties
app.persistence.enabled=true
app.persistence.jdbc-url=jdbc:mysql://127.0.0.1:3306/ykd_summer?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
app.persistence.username=你的数据库账号
app.persistence.password=你的数据库密码
app.persistence.maximum-pool-size=10

app.persistence.redis.enabled=true
app.persistence.redis.host=127.0.0.1
app.persistence.redis.port=6379
app.persistence.redis.password=

oss.image.enabled=true
oss.image.endpoint=你的OSS端点
oss.image.access-key-id=你的AccessKeyId
oss.image.access-key-secret=你的AccessKeySecret
oss.image.bucket-name=你的Bucket
```

也可以不写本地文件，改为环境变量：`PERSISTENCE_ENABLED`、`PERSISTENCE_JDBC_URL`、`PERSISTENCE_USERNAME`、`PERSISTENCE_PASSWORD`、`PERSISTENCE_REDIS_ENABLED`、`PERSISTENCE_REDIS_HOST`、`PERSISTENCE_REDIS_PORT`。`src/main/resources/application-local.properties` 仅用于本机 IDE 习惯配置，同样被忽略，但不建议把真实凭证随源码目录分发。

未配置 `app.persistence.enabled=true` 时，机器人保持原有内存模式，不要求 MySQL 或 Redis。

## 4. 启动顺序

先启动 Redis：

```powershell
Start-Process -FilePath D:\redis-server.exe -WorkingDirectory D:\ -WindowStyle Hidden
```

若安装了 `redis-cli`，可验证：

```powershell
redis-cli ping
```

预期返回 `PONG`。然后在项目根目录启动应用：

```powershell
mvn spring-boot:run -- --ilink.enabled=true
```

或使用已打包的 JAR。JDK 21 下建议带上模块开放参数：

```powershell
& 'D:\YOUKD\jdk-21.0.11\bin\java.exe' `
  --add-opens java.base/java.lang=ALL-UNNAMED `
  --add-opens java.base/java.util=ALL-UNNAMED `
  --add-opens java.base/java.io=ALL-UNNAMED `
  --add-opens java.base/java.util.zip=ALL-UNNAMED `
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED `
  --add-opens java.desktop/java.awt.font=ALL-UNNAMED `
  -jar target\ykd-summer-0.0.1-SNAPSHOT.jar --ilink.enabled=true
```

启动后检查：

```powershell
Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing
Invoke-WebRequest http://localhost:8080/api/ilink/status -UseBasicParsing
```

两项都应返回 HTTP `200`；iLink 状态应为 `CONNECTED`。

## 5. 已持久化的数据

- `chat_conversations`、`chat_messages`：仅在模型成功回答后写入。下次该用户发消息时，加载最近的聊天上下文。
- `async_tasks`、`task_events`：图片生成、改图的状态与事件。服务重启时未完成的 `RUNNING` 任务会标记为 `FAILED`，用户可通过原有重试工具再次提交。
- `asset_versions`：图片的 OSS object key、本地回退路径、版本、MIME 和描述元数据。图片字节仍在 OSS。
- `app_users`、`ai_usage_daily`、`outbox_events`、`admin_audit_logs`：为管理员系统、额度和可靠推送预留。

Redis 已作为应用连接初始化，并在启动时执行 `PING`；当前不保存唯一业务事实。后续扩展多实例或管理员系统时，用它实现跨实例限流、短期幂等键、任务抢占锁和缓存即可。

## 6. 常用查询

```sql
USE ykd_summer;

SELECT * FROM async_tasks ORDER BY started_at DESC LIMIT 20;

SELECT c.external_user_id, m.role, m.content, m.created_at
FROM chat_messages m
JOIN chat_conversations c ON c.id = m.conversation_id
ORDER BY m.id DESC
LIMIT 50;

SELECT external_user_id, asset_id, version, storage_provider, object_key, created_at
FROM asset_versions
ORDER BY created_at DESC
LIMIT 50;
```

## 7. 备份与恢复

备份 MySQL：

```powershell
mysqldump -u root -p --single-transaction --routines --events ykd_summer > backup-ykd-summer.sql
```

恢复时先创建空数据库，再执行：

```powershell
mysql -u root -p ykd_summer < backup-ykd-summer.sql
```

如果 Redis 保存了运行态缓存，可在停止 Redis 后备份 `dump.rdb`；它不替代 MySQL 备份。OSS 需要依照 Bucket 生命周期和版本策略独立备份。

## 8. 验证与排错

```powershell
mvn test

$env:PERSISTENCE_INTEGRATION='true'
$env:PERSISTENCE_PASSWORD='你的数据库密码'
mvn '-Dtest=PersistenceStoreIntegrationTest' test
Remove-Item Env:PERSISTENCE_INTEGRATION,Env:PERSISTENCE_PASSWORD
```

第二条集成测试会临时写入并清理聊天、图片任务、资产元数据，用于确认真实 MySQL 读写。它默认跳过，不会把密码写入仓库。

常见问题：

- `Communications link failure`：确认 MySQL 服务、3306 端口和 JDBC 地址。
- Redis 连接失败：确认 `redis-server.exe` 已运行，端口 6379 未被占用。
- Flyway 校验失败：不要修改已经执行过的迁移文件；新增 `V2__...sql` 修复结构。
- 图片能生成但找不到：检查 `asset_versions.object_key` 与 OSS Bucket、前缀和访问凭证。
- MySQL 8.4 的 Flyway 兼容警告：当前迁移可正常执行；升级 Spring Boot/Flyway 后应复测并消除该版本警告。

## 9. 后续 RAG

RAG 暂不启用。后续新增 `knowledge_bases`、`knowledge_documents`、`document_chunks` 和向量存储迁移即可。组织文档权限、来源和分块元数据仍保留在 MySQL；Embedding 与向量数据库可以独立替换，不绑定聊天模型厂商。
