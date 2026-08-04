"""网页搜索工具。

契约对齐 WeChatBot McpWebSearchTools：
  入参: {"query": "搜索关键词"}
  返回: {"results": [{"title":"...","url":"...","snippet":"..."}, ...]}

数据源：DuckDuckGo 免费搜索（无需 Key）。
如果配置了 BOCHA_API_KEY，则优先使用博查搜索（效果更好）。
"""

import json
import logging
import os

import httpx

logger = logging.getLogger(__name__)


def web_search(query: str) -> str:
    """搜索公开网页，返回实时信息。

    Args:
        query: 搜索关键词或完整问题。

    Returns:
        JSON 字符串，包含 results 数组，每项有 title/url/snippet。
    """
    bocha_key = os.getenv("BOCHA_API_KEY", "")
    if bocha_key:
        return _search_bocha(query, bocha_key)
    return _search_ddg(query)


def _search_ddg(query: str) -> str:
    """DuckDuckGo 免费搜索（无需 Key）。"""
    try:
        from duckduckgo_search import DDGS

        results = []
        with DDGS() as ddgs:
            for i, r in enumerate(ddgs.text(query, max_results=8)):
                results.append({
                    "title": r.get("title", ""),
                    "url": r.get("href", r.get("link", "")),
                    "snippet": r.get("body", r.get("snippet", "")),
                })
        return json.dumps({"results": results}, ensure_ascii=False)

    except Exception as exc:
        logger.warning("DuckDuckGo 搜索失败 query=%s: %s", query, exc)
        return json.dumps({"error": f"搜索失败: {exc}"}, ensure_ascii=False)


def _search_bocha(query: str, api_key: str) -> str:
    """博查搜索 API（需要 Key，效果更好）。"""
    try:
        resp = httpx.post(
            "https://api.bochaai.com/v1/web-search",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            json={"query": query, "count": 8},
            timeout=15,
        )
        resp.raise_for_status()
        data = resp.json()

        results = []
        for item in data.get("data", {}).get("webPages", {}).get("value", []):
            results.append({
                "title": item.get("name", ""),
                "url": item.get("url", ""),
                "snippet": item.get("summary", item.get("snippet", "")),
            })
        return json.dumps({"results": results}, ensure_ascii=False)

    except Exception as exc:
        logger.warning("博查搜索失败 query=%s: %s，回退到 DuckDuckGo", query, exc)
        return _search_ddg(query)
