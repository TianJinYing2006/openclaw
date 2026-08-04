# 数据安全加固设计（feature/security-hardening）

> 本文档记录 2026-08 数据安全加固的**实现方法与设计原理**。后续任何改动都应在 docs/ 下同步记录设计依据。

## 背景与目标

开发阶段项目存在以下数据安全暴露点：

| # | 暴露点 | 风险 |
|---|--------|------|
| 1 | `/api/ilink/send` 等调试接口无鉴权 | 局域网任意机器可主动发消息 |
| 2 | MCP Server 监听 `0.0.0.0:8090` 且无鉴权 | 局域网可调用试穿/抠图等 6 个工具 |
| 3 | contextToken（可主动向用户发消息的令牌）明文入库 | 泄露即被冒充发送 |
| 4 | AI 链路日志记录签名 URL | OSS 签名参数（临时凭证）进日志 |
| 5 | 图片/人体模板（全身照，敏感生物特征）明文落盘 | 磁盘被拷走即泄露 |
| 6 | 敏感本地目录无 ACL 收紧 | 局域网共享/备份整体外泄 |

设计总原则：**默认最小暴露、零侵入兼容、可降级不崩链路、不引入新框架、配置集中 + 环境变量注入**。

---

## 1. /api/** 本机访问限制

### 实现方法
- `ApiSecurityProperties`（`app.api-security.localhost-only`，默认 `true`）注册进 `@EnableConfigurationProperties`。
- `ApiSecurityFilter`：`OncePerRequestFilter` + `@Component`。路径以 `/api/` 开头且开关开启时，用 `InetAddress.getByName(remoteAddr).isLoopbackAddress()` 判断来源，非本机返回 403 JSON。

### 为什么这么设计
- 对比 Bearer token：token 会打断现有本机联调（PowerShell/Postman 每请求带头）；IP 白名单**零侵入**——本机行为不变，只切掉局域网来源。
- 用独立 Filter 而非塞进 Spring Security 链：**不依赖 admin 是否启用**，全局独立生效。
- `isLoopbackAddress()` 比硬编码字符串更标准，同时覆盖 IPv4/IPv6。
- 保留开关：多机联调是真实需求，不能堵死。

## 2. MCP Server 绑定本机

### 实现方法
- `server.py` 默认 `MCP_SERVER_HOST` 由 `0.0.0.0` 改为 `127.0.0.1`，`.env.example` 同步并注明跨机需显式改回。

### 为什么这么设计
- MCP 6 个工具无鉴权，**从网络层切断**比应用层加鉴权更彻底、成本最低。
- 本机 WeChatBot 经 `localhost:8090` 连接，行为不受影响；`0.0.0.0` 需显式配置，符合"默认最小暴露"。

## 3. contextToken 落库加密

### 实现方法
- `TokenCipher`：AES-256-GCM（随机 12 字节 IV + 128 位认证标签），密文格式 `enc:Base64(iv).Base64(cipher)` 单字符串。
- 密钥来自 `app.security.token-encryption-key`（32 字节 Base64，环境变量注入）。
- `JdbcILinkReplyContextPersistence`：写入 `encrypt`、读取 `decrypt`。
- **降级**：key 未配置 → 明文透传 + 一次性告警（含生成命令提示）。

### 为什么这么设计
- contextToken 是高权限令牌，明文进 MySQL 是真实风险，必须加密。
- 选 AES-GCM 而非 ECB/Base64：GCM 带认证标签，**密文被篡改会解密失败**，防伪造。
- **不复用 admin 的 `SessionCipher`**：它强依赖 `app.admin.session-encryption-key`，admin 未启用会抛异常拖垮消息链路；独立 key 让"发消息"与"管理后台"两个密钥域互不牵连。
- 降级明文 + 告警：沿用项目"Redis 不可用自动降级"惯例，保证未配 key 的部署不中断。
- `enc:` 前缀：自动区分新旧数据，旧明文直接透传，**平滑升级无需迁移**。

## 4. 日志 URL 脱敏（AiTraceLogger）

### 实现方法
- `preview()` 截断前先过 `redactUrls()`：正则匹配 `https?://...`，把 `?` 后 query 整体替换为 `?[query-redacted]`，只留 host+path。

### 为什么这么设计
- AI 链路日志记录用户输入、工具入参、模型输出，可能夹带 OSS 签名 URL——签名参数即临时凭证。
- 保留 host+path 足够排查来源，替换 query 不丢失可读性。
- 收敛在 `preview()` 单点：**一次改动覆盖所有日志出口**（用户输入、工具结果、模型回答）。

## 5. 图片/模板字节加密落盘

### 实现方法
- `TokenCipher` 新增 `encryptBytes/decryptBytes`（字节版，密文格式同字符串版）。
- `LocalImageAssetStore`：`saveVersion` 写盘前 `encrypt()`，`readBytes` 读盘后 `decrypt()`；`@Autowired(required=false)` setter 注入，直接 `new` 的测试场景不加密。

### 为什么这么设计
- 用户全身照（人体模板）属敏感生物特征，明文落盘是磁盘侧风险。
- 加密收敛在 store 读写边界：模板分析、试穿、图片编辑、MCP 上传等**所有下游拿到明文，单点改动全局生效**。
- 复用 `TokenCipher` 而非新建：一个 key 管所有敏感数据，配置/降级/告警逻辑一致。
- `enc:` 前缀识别旧文件：已存在明文图片无需迁移。

## 6. 敏感目录 ACL 收紧

### 实现方法
- `AssetDirectoryGuard`：`ApplicationReadyEvent` 后对 `.ai-assets`（含 images/delivery）、`.documents`、`.ilink` 收紧权限。
- Windows：`icacls /inheritance:r /grant:r 当前用户:(OI)(CI)F`（去继承 + 仅当前用户完全控制）；POSIX：递归 `rwx------`/`rw-------`。
- 失败只告警不阻断启动。

### 为什么这么设计
- 防止局域网共享、备份、同机其它账号读取明文隐私文件。
- Windows 原生 ACL（含继承控制）只有 `icacls` 能做，Java NIO 的 POSIX 权限 API 在 Windows 无对应语义。
- 固定目录清单 + 幂等执行，无配置负担。

---

## 配置项汇总

| 配置 | 默认 | 说明 |
|------|------|------|
| `app.api-security.localhost-only` | `true` | /api/** 仅本机；多机联调设 false |
| `app.security.token-encryption-key` | 空 | 32 字节 Base64；留空则 contextToken/图片明文存储并告警 |

## 兼容性与降级策略汇总

| 场景 | 行为 |
|------|------|
| 未配置加密 key | 明文透传 + 一次性 WARN，功能不受影响 |
| 读取旧明文数据/文件 | `enc:` 前缀识别，原样返回 |
| 配置 key 后写入新数据 | 自动加密，新旧数据共存可读 |
| **启用 key 后请保留** | 更换 key 后历史密文无法解密（显式抛异常） |
| 目录 ACL 失败 | 仅 WARN，不阻断启动 |
