# 部署到 Zeabur（最简托管路线）

> 目标：让别人通过公网域名访问本项目（用量页 / 后台 / API），无需自购服务器、无需配 nginx/traefik。
> 适合：作品集演示、面试展示、临时给同学试用。
> 前置：代码已在 GitHub `origin`（TianJinYing2006/openclaw.git），dev 分支。

## 0. 先决条件与已知限制

| 项 | 说明 |
|---|---|
| 代码来源 | Zeabur 直接拉 GitHub 仓库，**需先把最新改动 commit + push 到 origin**（见第 1 步） |
| 内存 | 单 app 约 1.5GB；5 服务同跑很可能超过免费额度，需付费套餐（约 $几/月）。建议给 app 设 2GB 上限 |
| RAGFlow | **不在部署栈内**。完整聊天 RAG 检索需另跑 RAGFlow；用量页 / Langfuse 不依赖它，演示够用 |
| 微信 iLink | 真要通过微信收消息，需**已备案域名 + 公网 HTTPS**，`*.zeabur.app` 子域过不了公众号回调校验。演示用后台/API 即可 |
| 平台架构 | Zeabur 跑 linux/amd64；本地若在 Apple Silicon 构建需 `--platform linux/amd64`（Windows 本机无此问题） |
| 端口 | 一个服务默认公开一个端口。本配置主端口 8080；后台/用量页(8081)在 Dashboard → Networking 开 Port Forwarding 拿独立地址 |

## 1. 提交并推送最新改动（需你确认后我执行）

当前 dev 分支有未提交改动（用量页、Langfuse、deploy.md、本文件等）。Zeabur 拉的是 GitHub 上的版本，必须推送：

```bash
git add -A && git commit -m "feat: 用量页 + Langfuse + Zeabur 部署配置" && git push origin dev
```

> ⚠️ 推送是外部操作，需你确认。也可你自己推。

## 2. 创建 Zeabur 项目并连仓库

1. 注册/登录 https://zeabur.com （可用 GitHub 登录）。
2. New Project → Deploy from GitHub repo → 选 `openclaw` → 分支 `dev`。
3. 平台读取 `zeabur.yml`，但 MySQL/Redis 建议手动先加（见下）。

## 3. 添加数据库（一键模板，自动注入变量）

在 Project 里依次 Add Service：
- **MySQL**：记下它自动生成的 `MYSQL_HOST/PORT/USERNAME/PASSWORD/DATABASE`（无需手动写，平台会注入给其他服务）。
- **Redis**：自动生成 `REDIS_HOST/PORT/PASSWORD`。

> 应用已支持 `PERSISTENCE_REDIS_PASSWORD`，直接引用 `${REDIS_PASSWORD}` 即可，无需改代码。

## 4. 部署三个自定义服务

`zeabur.yml` 已声明，平台会自动构建；也可手动：

| 服务 | 类型 | 说明 |
|---|---|---|
| `app` | Dockerfile（仓库根） | 主应用，公开端口 8080，后台 8081 开 Port Forwarding |
| `mcp-server` | Dockerfile（`./mcp-server`） | 构建 `./mcp-server/Dockerfile` |
| `qdrant` | 镜像 `qdrant/qdrant:v1.14.1` | 暴露 6333/6334 |

## 5. 配置环境变量（Dashboard → 各服务 → Variables）

必填：
- `DASHSCOPE_API_KEY`：阿里云百炼/通义密钥（聊天与 Embedding 必需）
- `APP_ADMIN_PASSWORD`：后台强密码（别用默认 change-me）

选填（用量页 / 可观测）：
- `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY`：jp.cloud.langfuse.com 的密钥
- `LANGFUSE_OTLP_HEADERS`：`Authorization=Basic <base64(pk:sk)>,x-langfuse-ingestion-version=4`
  生成：`printf '%s' "pk:sk" | base64`
- `SPRING_AI_MODEL`：默认 qwen3.7-plus
- `ILINK_ENABLED`：演示设 false

下列由 MySQL/Redis 模板自动注入，app 已在 `zeabur.yml` 引用，**无需手填**：
`MYSQL_*`、`REDIS_*`；`QDRANT_HOST` 已写死为 `qdrant`。

## 6. 资源与验证

- 给 `app` 设内存上限 ≈ 2GB（Dashboard → app → Settings → Resources）。
- 部署完成后：
  ```bash
  curl https://<你的>.zeabur.app/actuator/health   # 期望 200
  ```
- 用量页：`https://<你的>.zeabur.app:8081/...`（Port Forwarding 地址，需后台密码）。

## 7. 排错

- **构建失败**：看 Build Logs。多为 Maven 依赖下载慢/超时，重试或换付费构建额度。
- **app 起不来**：查 Runtime Logs。常见是 MySQL/Redis 变量未注入（确认第 3 步模板已加且同 Project）。
- **Redis 鉴权失败**：确认 `PERSISTENCE_REDIS_PASSWORD=${REDIS_PASSWORD}` 已传（应用支持，见 application.properties:29）。
- **Qdrant 连不上**：确认 `QDRANT_HOST=qdrant` 且 qdrant 服务在同 Project 网络中。

## 8. 与「自购服务器」方案对比

| | Zeabur | 阿里云轻量 + nginx |
|---|---|---|
| 上手 | 连仓库即部署 | 需买机、装 Docker、配反代 |
| 成本 | 按量，演示约 $几/月 | ¥298/年（含 300 券近乎免费） |
| 控制 | 中 | 高 |
| 长期运营 | 适合 | 更适合（接真实公众号需备案域名） |
