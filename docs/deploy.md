# WeChatBot 服务器部署手册

> 目标：把整个应用（含管理后台「Token 用量分析」页）跑在一台 Linux 服务器上。
> 本地 Windows 开发用的 `taskkill` / `bin\mvn` 路径只在本机有效，compose 在 Linux 上用 `docker` / `mvn` 即可，无需关心。

## 0. 前置条件
- 一台 Linux 服务器（2C4G 起步，推荐 4C8G），已装 **Docker ≥ 24** 与 **docker compose ≥ v2**。
- 开放端口：`80` / `443`（建议走 nginx+TLS）、`8080`（主应用，可选）、`8081`（管理后台）。
- 服务器能**出网**到 `jp.cloud.langfuse.com`（用量页拉数据 + 链路导出）。
- 手头密钥：`DASHSCOPE_API_KEY`、`LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY`。

## 0.1 购买阿里云轻量应用服务器（学生认证）
学生认证已具备，按此购买最省心（轻量应用服务器比 ECS 更简单：自带防火墙面板、可一键选 Docker 镜像）：

1. 登录 [阿里云控制台](https://home.console.aliyun.com)，确认**学生认证**已完成（实名 + 学信网/学生证）。
2. 进入 **云翼计划 / 学生优惠** 专区（首页搜「轻量应用服务器 学生」或「云翼计划」）；若只想短期演示，先看 **飞天加速计划**（学生可领免费 ECS 额度，优先用）。
3. 选 **轻量应用服务器**，关键参数：
   - 规格：**必须 2 核 4G 起步**（本项目 5 个容器同跑：app 自身 `-Xmx2g` + MySQL + Redis + Qdrant；2G 物理内存必 OOM，4G 是硬下限，预算足直接 4 核 8G）。
   - 镜像：选 **Docker 应用镜像**（Ubuntu 22.04 + 已装 Docker），或 Alibaba Cloud Linux 3（自行装 Docker）。
   - 地域：就近（华东1 杭州 / 华南1 深圳）。
   - 带宽：3–5 Mbps（固定或按流量均可，演示够用）。
   - 时长：推荐**包年**（选「同价续费」活动款锁价，避开「首年便宜、续费翻倍」）。
> **💡 用你手上的 300 元学生无门槛券（高校计划，学信网认证后每年领 1 张，有效期 1 年）：**
> - 目标机型：**轻量 2 核 4G「普惠」活动价 ¥298/年**（≈¥25/月，长期活动、支持同价续费）。
> - 结算页勾选 300 元券：**允许叠加 → 首年 ≈ ¥0（白嫖）**；若提示「不可与其他优惠叠加」→ 直接付 ¥298（¥25/月也很便宜，券留着以后用）。
> - ⚠️ 别为用券去选 **2 核 2G 云翼免费款**：内存不够跑这套栈，白送也用不了。
> - ⚠️ 券有效期 1 年、过期作废，领了尽快下单。
>
> 2026-08 真实活动价对照：
>
> | 方案 | 配置 | 价格 | 点评 |
> |---|---|---|---|
> | 轻量·普惠 | 2核4G | ¥298/年（≈¥25/月） | **推荐**，同价续费 |
> | 轻量·抢购(新用户秒杀) | 2核4G | ¥9.9/月 / ¥199/年 | 每日 10/15 点秒杀，限无订单新用户，**不可叠券** |
> | 云翼·学生默认 | 2核2G | ¥9.5–14/月 | ❌ 内存不够 |
> | 你最初看的链接 | 2核2G | ¥34/月 | ❌ 又贵又不够 |

4. 支付后，在 **轻量应用服务器控制台** 看到 **公网 IP** 与 **防火墙** 页。
5. **防火墙放行端口**：`22`(SSH)、`80`、`443`、`8080`、`8081`（用量页）。轻量面板加规则比 ECS 安全组直观。
6. SSH 登录：`ssh root@<公网IP>`（或用控制台自带 **Workbench / 在线终端**，免客户端）。
7. 若镜像未带 Docker，装：
   ```bash
   curl -fsSL https://get.daocloud.io/docker | sh
   docker --version && docker compose version   # 验证
   ```
8. 继续下方「1. 上传代码」即可。
   > 安全：admin 后台（8081）**勿公网裸暴露**，按「6. 安全加固」用 nginx + TLS + basic auth 护住，或仅面试时临时开放。

## 0.2 配置收口（2 核 4G 跑全套栈）
4G 内存要带 5 个容器，需收一下默认参数，否则容易 OOM：

- **app 堆内存**：compose 的 `app` 服务 `environment` 加 `JAVA_TOOL_OPTIONS`，从默认 2g 降到 **1500m**（演示低流量足够）：
  ```yaml
  # docker-compose.yml 的 app 服务 environment 内加：
  - JAVA_TOOL_OPTIONS=-Xmx1500m -Xms512m
  ```
- **MySQL**：`command` 加 `--innodb-buffer-pool-size=256M`（轻量演示足够）。
- **swap 兜底**：建 2GB swap 防偶发 OOM：
  ```bash
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  ```
- 预算足（4 核 8G）可保持 `-Xmx2g`，无需上面收口。

> 验证：部署后 `docker compose ps` 全 healthy，`free -h` 看内存/swap 占用；若 app 频繁重启即 OOM，先降 `-Xmx` 或加 swap。

## 1. 上传代码
```bash
# 方式 A：git（推荐）
git clone <your-repo> /opt/wechatbot && cd /opt/wechatbot
# 方式 B：本地打包后 scp（若不用 git）
# 本地：docker compose build  或 直接把整个目录 rsync 上去
```

## 2. 准备环境变量
```bash
cp .env.example .env
# 编辑 .env，至少填：DASHSCOPE_API_KEY、LANGFUSE_PUBLIC_KEY/SECRET_KEY、APP_ADMIN_PASSWORD
```

### 关键一步：生成 Langfuse OTLP 头
用量页能出数据，**必须**让应用把链路导出到 Langfuse（否则没有 trace 可查）。导出头需要 Base64：
```bash
printf '%s' "你的pk:你的sk" | base64
# 例：printf '%s' "pk-lf-xxxx:sk-lf-xxxx" | base64  -> 得到一长串
```
把结果填进 `.env` 的 `LANGFUSE_OTLP_HEADERS`：
```
LANGFUSE_OTLP_HEADERS=Authorization=Basic <上面那一长串>,x-langfuse-ingestion-version=4
```
> 说明：`LANGFUSE_PUBLIC_KEY` / `SECRET_KEY` 用于「用量页查询」，`LANGFUSE_OTLP_HEADERS` 用于「链路导出」。两者都要有，页面才有数据。

## 3. 构建并启动
```bash
docker compose build          # 构建 app（多阶段 Maven 打包）+ mcp-server 镜像
docker compose up -d          # 后台拉起 mysql/redis/qdrant/mcp-server/app
docker compose ps             # 确认 5 个服务都是 healthy/running
```

## 4. 验证
```bash
docker compose logs -f app    # 看到 "Started YkdSummerApplication" + "MySQL connectivity verified" 即正常
curl -fsS http://127.0.0.1:8080/actuator/health   # 期望 200
```
打开 `http://<服务器IP>:8081/admin/usage` → 用 `.env` 里的 `APP_ADMIN_USERNAME/PASSWORD` 登录 → 看到 Token 用量四维度（总览/按模型/按用户/历史趋势）。

## 5. 让 bot 真在服务器回微信（可选）
- 设 `.env`：`ILINK_ENABLED=true`
- `docker compose up -d app` 重启
- 首次需托管登录一次（iLink 持久化会话），之后 7×24 自动运行

## 6. 安全加固（强烈建议）
管理后台只靠密码登录，且无 2FA/限流，**不要公网裸暴露 8081**。
推荐在服务器前置 nginx + TLS + basic auth（或 IP 白名单 / VPN）：
```nginx
# /etc/nginx/conf.d/wechatbot-admin.conf 片段
server {
    listen 443 ssl;
    server_name admin.your-domain.com;
    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;
    location / {
        auth_basic "WeChatBot Admin";
        auth_basic_user_file /etc/nginx/.htpasswd_admin;   # htpasswd -bc 生成
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
    }
}
```
主应用 `8080` 若只对内（iLink 走出站连接，无需公网入站），可不开防火墙端口。

## 7. 常见问题
- **用量页显示「未配置 Langfuse」**：`.env` 漏了 `LANGFUSE_PUBLIC_KEY` / `SECRET_KEY`。
- **用量页显示「限流中」**：Langfuse 查询接口 15 次/分钟（与 Web UI 共享配额）。等一会儿刷新，或关掉 Langfuse Web UI 轮询再刷新。
- **8081 访问不到**：compose 已映射 `8081:8081`；检查服务器安全组/Linux 防火墙是否放行。
- **RAGFlow 缺失**：`docker-compose.yml` 未包含 RAGFlow。**用量页不依赖 RAGFlow**，可单独先上；要完整穿搭检索再补一个 RAGFlow 服务并配置 `RAGFLOW_HOST`。
- **历史数据为空**：用量只统计「部署后」产生的 trace（OTel 不回填历史）。

## 8. 升级
```bash
git pull
docker compose build app
docker compose up -d app
```
