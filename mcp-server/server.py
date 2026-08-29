#!/usr/bin/env python3
"""WeChatBot MCP Server — 统一外部能力服务。

一个 Server 暴露全部 6 个工具，供 WeChatBot 通过 SSE 连接调用：
  - get_weather              天气查询（wttr.in 免费 API）
  - web_search               网页搜索（DuckDuckGo / 博查）
  - garment_cutout           服装抠图（rembg 本地模型）
  - garment_revise           服装草稿修订（同抠图逻辑）
  - wardrobe_photo_analysis  衣橱照片识别（百炼 qwen-vl-max）
  - virtual_try_on           虚拟试衣（火山引擎 doubao-seedream-4.0）

启动后监听 http://localhost:8090（端口可通过 .env 配置）。
WeChatBot 配置 spring.ai.mcp.client.sse.connections 指向此地址即可。
"""

import logging
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

# 加载 .env 环境变量
from dotenv import load_dotenv
load_dotenv()

# 确保 tools 包可导入
sys.path.insert(0, str(Path(__file__).parent))

from mcp.server.fastmcp import FastMCP

from tools import weather, search, analysis, cutout, tryon

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("mcp-server")


def _url_label(url: str) -> str:
    """日志脱敏：只保留 host + path，去掉 query（可能含 OSS 签名或 token）。"""
    if not url:
        return "none"
    try:
        parts = urlsplit(url)
        return (f"{parts.hostname or ''}{parts.path or ''}")[:80]
    except Exception:
        return "[url]"

# 读取 host 和 port（在构造 FastMCP 之前）
# 默认仅绑定本机，防止局域网其它机器直接调用；需要跨机访问时改为 0.0.0.0
_host = os.getenv("MCP_SERVER_HOST", "127.0.0.1")
_port = int(os.getenv("MCP_SERVER_PORT", "8090"))

# 创建 MCP Server 实例
# stateless_http=True: 每个请求创建独立 transport，无需 session ID
#   （Java mcp-core 0.18.3 发送非初始化请求时不携带 session ID，stateful 模式会返回 400）
# json_response=True: 返回 JSON 而非 SSE 流，避免 Java 客户端 SSE 解析兼容性问题
mcp = FastMCP("wechatbot-mcp-server",
              host=_host, port=_port,
              stateless_http=True,
              json_response=True)


# ============================================================
# 注册工具
# 工具名和参数名必须与 WeChatBot 客户端代码完全匹配
# ============================================================

@mcp.tool()
def get_weather(city: str) -> str:
    """查询指定城市的当前天气。返回 JSON 含 province/city/weather/temperature 等字段。

    Args:
        city: 城市名称，如"杭州"、"北京市"
    """
    logger.info("工具调用 get_weather: city=%s", city)
    return weather.get_weather(city)


@mcp.tool()
def web_search(query: str) -> str:
    """搜索公开网页中的实时信息、新闻和热点。返回 JSON 含 results 数组。

    Args:
        query: 要搜索的关键词或完整问题
    """
    logger.info("工具调用 web_search: query=%s", query)
    return search.web_search(query)


@mcp.tool()
def garment_cutout(sourceImageUrl: str, displayName: str = "",
                    category: str = "", colorPrimary: str = "",
                    instruction: str = "") -> str:
    """从照片中提取服装单品并去除背景，返回 PNG 透明背景图的 Base64。

    Args:
        sourceImageUrl: 源图签名 URL，服务端自行下载
        displayName: 候选显示名（如"白色T恤"），辅助理解
        category: 标准类目（如 T_SHIRT、JEANS）
        colorPrimary: 主色（如 WHITE）
        instruction: 用户修改指令；为空表示忠实提取
    """
    logger.info("工具调用 garment_cutout: url=%s, category=%s",
                _url_label(sourceImageUrl), category)
    return cutout.garment_cutout(sourceImageUrl, displayName, category,
                                  colorPrimary, instruction)


@mcp.tool()
def garment_revise(sourceImageUrl: str, displayName: str = "",
                   category: str = "", colorPrimary: str = "",
                   instruction: str = "") -> str:
    """基于已有草稿图做局部修改，返回修改后的 PNG Base64。

    Args:
        sourceImageUrl: 草稿图签名 URL
        displayName: 候选显示名
        category: 标准类目
        colorPrimary: 主色
        instruction: 用户修改指令（如"整体窄一点"）
    """
    logger.info("工具调用 garment_revise: url=%s, instruction=%s",
                _url_label(sourceImageUrl), instruction)
    return cutout.garment_revise(sourceImageUrl, displayName, category,
                                  colorPrimary, instruction)


@mcp.tool()
def wardrobe_photo_analysis(imageUrl: str) -> str:
    """分析衣橱照片，识别其中的服装单品，返回结构化候选 JSON。

    Args:
        imageUrl: 衣橱照片的签名 URL，服务端自行下载分析
    """
    logger.info("工具调用 wardrobe_photo_analysis: url=%s", _url_label(imageUrl))
    return analysis.wardrobe_photo_analysis(imageUrl)


@mcp.tool()
def virtual_try_on(personImageUrl: str, garmentImageUrl: str,
                   garmentCategory: str = "") -> str:
    """接收人物图和衣物图 URL，生成虚拟试穿效果图。

    Args:
        personImageUrl: 人物全身模板图 URL
        garmentImageUrl: 衣物展示图 URL
        garmentCategory: 衣物类目（如 OUTER/T_SHIRT/JEANS）
    """
    logger.info("工具调用 virtual_try_on: person=%s, garment=%s",
                _url_label(personImageUrl), _url_label(garmentImageUrl))
    return tryon.virtual_try_on(personImageUrl, garmentImageUrl, garmentCategory)


# ============================================================
# 启动
# ============================================================

if __name__ == "__main__":
    logger.info("=" * 60)
    logger.info("WeChatBot MCP Server 启动中...")
    logger.info("监听地址: http://%s:%s", _host, _port)
    logger.info("已注册工具: get_weather, web_search, garment_cutout,")
    logger.info("             garment_revise, wardrobe_photo_analysis,")
    logger.info("             virtual_try_on")
    logger.info("DASHSCOPE_API_KEY: %s",
                "已配置" if os.getenv("DASHSCOPE_API_KEY", "") else "未配置")
    logger.info("BOCHA_API_KEY: %s",
                "已配置" if os.getenv("BOCHA_API_KEY", "") else "未配置（使用 cn.bing.com 免费搜索）")
    logger.info("=" * 60)

    # 使用 streamable-http 传输 + hypercorn ASGI 服务器（支持 h2c HTTP/2 升级）。
    # Java 21 HTTP 客户端默认发送 HTTP/2 升级请求（Upgrade: h2c），
    # uvicorn 不支持 h2c，导致请求 body 丢失（Content-Length 有值但 body 为空）。
    # hypercorn 支持 h2c，能正确处理 HTTP/2 升级并传递 body。
    # 同时保留 Accept 头注入中间件（Java 客户端不发送 Accept 头）。
    import asyncio
    from hypercorn.asyncio import serve
    from hypercorn.config import Config as HypercornConfig

    class AcceptHeaderASGIMiddleware:
        """ASGI 中间件：为 /mcp 请求注入 Accept 头。"""
        REQUIRED_ACCEPT = b"application/json, text/event-stream"

        def __init__(self, app):
            self.app = app

        async def __call__(self, scope, receive, send):
            if (scope["type"] == "http"
                    and scope.get("path", "").rstrip("/") == "/mcp"):
                original_headers = scope.get("headers", [])
                filtered = [(k, v) for k, v in original_headers if k.lower() != b"accept"]
                filtered.append((b"accept", self.REQUIRED_ACCEPT))
                scope = dict(scope)
                scope["headers"] = filtered
            await self.app(scope, receive, send)

    hypercorn_config = HypercornConfig()
    hypercorn_config.bind = [f"{_host}:{_port}"]
    hypercorn_config.h2 = True  # 启用 HTTP/2 cleartext (h2c)
    hypercorn_config.accesslog = None  # 关闭访问日志（uvicorn 风格日志由下方管理）
    hypercorn_config.errorlog = None
    hypercorn_config.use_reloader = False

    http_app = mcp.streamable_http_app()
    app = AcceptHeaderASGIMiddleware(http_app)
    asyncio.run(serve(app, hypercorn_config))
