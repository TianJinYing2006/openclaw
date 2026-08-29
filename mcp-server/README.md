# WeChatBot MCP Server

为 WeChatBot 项目提供外部能力的统一 MCP Server，一个进程暴露全部 6 个工具。

## 工具一览

| 工具名 | 功能 | 数据源 | 需要 API Key |
|--------|------|--------|-------------|
| `get_weather` | 天气查询 | wttr.in 免费 API | 否 |
| `web_search` | 网页搜索 | DuckDuckGo（默认）/ 博查 | 否（博查需要） |
| `garment_cutout` | 服装抠图 | rembg 本地模型 | 否 |
| `garment_revise` | 服装草稿修订 | rembg 本地模型 | 否 |
| `wardrobe_photo_analysis` | 衣橱照片识别 | 百炼 qwen-vl-max | 是（DASHSCOPE_API_KEY） |
| `virtual_try_on` | 虚拟试衣 | 百炼图像 API（占位） | 是（DASHSCOPE_API_KEY） |

## 前置条件

1. **Python 3.10+**（[下载地址](https://www.python.org/downloads/)，安装时勾选 Add to PATH）
2. **百炼 API Key**（仅衣橱识别和试衣需要，与 WeChatBot 共用同一个 Key）

## 快速启动

### 方式一：一键启动（推荐）

```cmd
# 1. 进入 mcp-server 目录
cd d:\项目\WeChatBot\mcp-server

# 2. 双击运行（自动创建虚拟环境、安装依赖、启动）
start.bat
```

### 方式二：手动启动

```cmd
cd d:\项目\WeChatBot\mcp-server

# 1. 创建虚拟环境
python -m venv .venv

# 2. 激活虚拟环境
.venv\Scripts\activate

# 3. 安装依赖
pip install -r requirements.txt

# 4. 复制并编辑环境变量
copy .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY

# 5. 启动
python server.py
```

启动成功后会显示：
```
============================================================
WeChatBot MCP Server 启动中...
监听地址: http://0.0.0.0:8090
已注册工具: get_weather, web_search, garment_cutout,
             garment_revise, wardrobe_photo_analysis,
             virtual_try_on
DASHSCOPE_API_KEY: 已配置
BOCHA_API_KEY: 未配置（使用 DuckDuckGo）
============================================================
```

## 配置 WeChatBot 连接

MCP Server 启动后，在 WeChatBot 的 `application-local.properties` 中添加：

```properties
# === MCP Server SSE 连接 ===
spring.ai.mcp.client.sse.connections.unified-mcp.url=http://localhost:8090
spring.ai.mcp.client.sse.enabled=true

# === 切换全部 provider 为 mcp ===
app.web-search.provider=mcp
app.fashion.cutout.provider=mcp
app.fashion.analysis.provider=mcp
app.fashion.tryon.provider=mcp
app.weather.provider=mcp
```

也可以只切换部分功能（混合使用本地实现和 MCP）：

```properties
# 只用 MCP 做天气和搜索
app.weather.provider=mcp
app.web-search.provider=mcp
# 其他功能继续用本地实现
```

## 环境变量说明

| 变量名 | 必填 | 说明 |
|--------|------|------|
| `DASHSCOPE_API_KEY` | 衣橱识别/试衣需要 | 百炼 API Key，与 WeChatBot 共用 |
| `DASHSCOPE_BASE_URL` | 否 | 百炼接口地址，默认 `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `VISION_MODEL` | 否 | 视觉模型名，默认 `qwen-vl-max` |
| `BOCHA_API_KEY` | 否 | 博查搜索 Key，不填则用 DuckDuckGo |
| `MCP_SERVER_HOST` | 否 | 监听地址，默认 `0.0.0.0` |
| `MCP_SERVER_PORT` | 否 | 监听端口，默认 `8090` |

## 各工具实现说明

### 天气查询（get_weather）
调用 [wttr.in](https://wttr.in) 免费 API，无需 Key。返回中文天气描述、温度、风向、风力等级等。

### 网页搜索（web_search）
默认使用 DuckDuckGo 免费搜索（无需 Key）。配置 `BOCHA_API_KEY` 后自动切换到博查搜索（效果更好，中文优化）。

### 服装抠图（garment_cutout / garment_revise）
使用 [rembg](https://github.com/danielgatis/rembg) 本地 AI 模型去除背景，无需 API Key。首次运行自动下载约 170MB 模型文件（u2net），之后离线运行。

抠图和修订当前使用相同逻辑（rembg 不区分语义）。替换为专业模型时可分别实现。

### 衣橱识别（wardrobe_photo_analysis）
调用百炼 qwen-vl-max 视觉模型，分析图片中的服装单品，返回结构化候选列表（类目、颜色、风格、质量分等）。

### 虚拟试衣（virtual_try_on）
调用火山引擎 [doubao-seedream-4.0](https://www.volcengine.com/docs/82379/1824718) 图像生成 API 的多图融合能力：传人物全身照 + 服装单品图，通过提示词把服装换到模特身上。

- 需要先在火山方舟开通模型并获取 API Key，填入 `.env` 的 `ARK_API_KEY`（见 `.env.example`）
- 按生成图片张数计费（约 0.2 元/张），生成失败不收费
- 支持 `ARK_MODEL` / `ARK_SIZE` / `ARK_TIMEOUT` / `ARK_RETRIES` 配置
- 对瞬时错误（429/5xx/连接超时）自动指数退避重试

## 常见问题

### Q: 启动报错 ModuleNotFoundError
确保已激活虚拟环境并安装依赖：
```cmd
.venv\Scripts\activate
pip install -r requirements.txt
```

### Q: rembg 首次运行很慢
rembg 首次调用时需要下载模型文件（约 170MB），下载后会缓存到用户目录，后续启动正常。

### Q: WeChatBot 启动报 SSE 连接失败
1. 确认 MCP Server 已启动（终端应显示"监听地址"日志）
2. 确认端口一致（默认 8090）
3. 确认 `application-local.properties` 中 SSE 连接 URL 正确

### Q: 衣橱识别返回 "DASHSCOPE_API_KEY 未配置"
编辑 `.env` 文件，填入你的百炼 API Key。与 WeChatBot 项目共用同一个 Key。

### Q: 想分开部署不同功能
修改 `server.py`，只注册需要的工具，启动多个进程监听不同端口。然后在 WeChatBot 配置多组 SSE 连接。

### Q: 如何验证某个工具是否正常工作
可以用 curl 手动测试 SSE 连接：
```cmd
curl http://localhost:8090/sse
```
如果返回 event stream，说明 Server 正常运行。

## 项目结构

```
mcp-server/
├── server.py              # 主入口，注册所有工具
├── start.bat              # Windows 一键启动脚本
├── requirements.txt       # Python 依赖
├── .env.example           # 环境变量模板
├── .env                   # 你的实际配置（不提交 Git）
├── .venv/                 # 虚拟环境（自动创建）
└── tools/
    ├── __init__.py        # 包初始化
    ├── weather.py         # 天气查询（wttr.in）
    ├── search.py          # 网页搜索（DuckDuckGo/博查）
    ├── analysis.py        # 衣橱识别（百炼 qwen-vl-max）
    ├── cutout.py          # 服装抠图（rembg）
    └── tryon.py           # 虚拟试衣（百炼图像 API）
```
