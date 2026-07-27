# 本地机器人管理站

管理站随现有 Spring Boot 进程启动，不需要 Node.js 或第二个 Java 进程。

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:8080` | 现有 iLink 机器人服务、`/api/ilink/*` 调试接口和健康检查 |
| `http://127.0.0.1:8081/admin` | 仅本机管理员可用的用户、机器人实例、二维码和审计页面 |

## 本机配置

将以下配置放到项目根目录、已被 Git 忽略的 `application-local.properties`：

```properties
app.admin.enabled=true
app.admin.username=admin
app.admin.password=replace-with-a-strong-local-password
app.admin.session-encryption-key=replace-with-32-byte-base64-key
```

生成会话主密钥的 PowerShell 命令：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

主密钥只保留在本机。它用于 AES-256-GCM 加密 iLink token 和消息游标后再写入 MySQL；丢失该主密钥后，已保存的登录态无法恢复，需要重新扫码。

## 首次启用

1. 用项目的 `scripts/restart-bot.ps1` 重启 Java 进程。
2. 打开 `http://127.0.0.1:8081/admin`，使用本地管理员账号登录。
3. 若 `.ilink/session.properties` 中已有旧单账号会话，第一次启用时会自动迁为 `管理员本人` 的首个实例，然后删除旧明文会话文件。
4. 新增用户后点“生成二维码”，用该用户对应的微信扫码。扫码成功后，实例立即保存独立会话并开始轮询。

## 数据与恢复规则

- 一个平台用户只保留一个当前实例，聊天、工具状态、任务、资产和用量都以实例范围隔离。
- 解绑会停止实例并将其归档；加密登录态、聊天、用量和资产索引永久保留。
- 恢复旧实例时，当前实例会自动归档，避免两个微信账号的数据混在同一用户下。
- 永久删除只能从归档实例操作，必须输入用户名和原因。系统记录管理员、时间、目标和理由，并删除该实例关联的会话凭证、聊天索引、任务、用量和资产索引。

## 运维说明

- MySQL 是事实来源；Redis 只用于 24 小时消息去重、按用户/消息类型的滑动窗口限流，以及 30 秒实时检索缓存。Redis 不可用时会自动退回受限本地缓存，不能删除或替代 MySQL 数据。
- Redis 键名只保存经过 SHA-256 处理的标识，不直接包含微信 ID、消息 ID 或用户查询。可用 `app.persistence.redis.key-prefix` 和 `app.persistence.redis.message-dedup-ttl` 调整命名空间与去重保留时间。
- 图片、音频、视频和文档原件继续保存在 OSS 或本地资产目录，MySQL 只保存索引、版本和归属。
- 管理页的 Tool 统计来自 `ai_usage_events`：每次被模型实际执行的 Spring AI Tool 都会记录工具名、成功或异常、耗时和实例归属；不保存工具参数、聊天正文或异常正文。`Tool 异常`仅表示 Java 回调实际抛出了异常，工具正常返回的一段“查询失败”文字不会被误判为异常。
- 当前管理站限制为单机最多 20 个活跃实例，`app.admin.max-instances` 不能超过 20。
- 生产化前应把 `app.admin.password` 和 `app.admin.session-encryption-key` 改为部署环境变量，并将 `app.admin.bind-address` 保持为 `127.0.0.1` 或在反向代理处做额外认证。
