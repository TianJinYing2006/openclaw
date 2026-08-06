"""网页搜索工具。

契约对齐 WeChatBot McpWebSearchTools：
  入参: {"query": "搜索关键词"}
  返回: {"results": [{"title":"...","url":"...","snippet":"..."}, ...]}

数据源：cn.bing.com 免费搜索（无需 Key，国内可访问）。
如果配置了 BOCHA_API_KEY，则优先使用博查搜索（效果更好）。
"""

import json
import logging
import os
import re
from html import unescape

import httpx

logger = logging.getLogger(__name__)

_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 口语化提问词。cn.bing 对“今年/最新”等时间修饰词很敏感，容易把整句解析成其他主题。
_STOP_WORDS = ("是谁", "是什么", "是怎么", "为什么", "怎么样", "怎么", "如何", "哪个",
               "哪一个", "哪些", "呢", "吗", "啊", "呀", "请问", "一下", "到底",
               "究竟", "今年", "最新", "当前", "现在", "本届", "上一届", "最近")


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
    return _search_bing_cn(query)


def _clean_query(query: str) -> str:
    """去掉口语化提问词，保留核心关键词。"""
    cleaned = query
    for word in _STOP_WORDS:
        cleaned = cleaned.replace(word, " ")
    cleaned = re.sub(r"[，。？！?！、；：,.?!;:]+", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def _core_phrase(query: str) -> str:
    """从 query 提取最长连续中文片段（>=2 字）作为核心词，如“世界杯冠军”。"""
    segments = re.findall(r"[\u4e00-\u9fff]{2,}", query)
    return max(segments, key=len) if segments else ""


def _parse_bing(html: str) -> list:
    """解析必应搜索结果页，返回 [{"title","url","snippet"}, ...]。"""
    results = []
    # 每个自然结果块 <li class="b_algo">；标题/链接在 h2 > a，摘要可选地在 p
    for block in re.findall(r'<li class="b_algo".*?</li>', html, re.S):
        match = re.search(r'<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>(.*?)</a></h2>', block, re.S)
        if not match:
            continue
        url = match.group(1)
        title = unescape(re.sub(r"<[^>]+>", "", match.group(2))).strip()
        caption = re.search(r"<p[^>]*>(.*?)</p>", block, re.S)
        snippet = unescape(re.sub(r"<[^>]+>", "", caption.group(1))).strip() if caption else ""
        results.append({"title": title, "url": url, "snippet": snippet})
        if len(results) >= 8:
            break
    return results


def _fetch_bing(query: str) -> list:
    resp = httpx.get(
        "https://cn.bing.com/search",
        params={"q": query},
        headers={"User-Agent": _UA},
        timeout=15,
        follow_redirects=True,
    )
    resp.raise_for_status()
    return _parse_bing(resp.text)


def _search_bing_cn(query: str) -> str:
    """必应国内版 cn.bing.com HTML 解析搜索（无需 Key）。

    模型传来的 query 常是整句口语（如“今年世界杯冠军是谁”），cn.bing 对这类长句
    容易跑题；这里先做一次核心词重搜，保证返回内容与问题相关。
    """
    try:
        results = _fetch_bing(query)
        core = _core_phrase(_clean_query(query))
        # 首搜结果里没有一个标题包含完整核心词（如“世界杯冠军”）时，视为跑题并重搜
        if core and not any(core in r["title"] for r in results):
            logger.info("cn.bing 结果跑题 query=%s, 用核心词重搜: %s", query, core)
            results = _fetch_bing(core)

        if not results:
            logger.warning("cn.bing 未解析到结果 query=%s", query)
            return json.dumps({"error": "搜索失败: 未解析到任何结果"}, ensure_ascii=False)
        return json.dumps({"results": results}, ensure_ascii=False)

    except Exception as exc:
        logger.warning("cn.bing 搜索失败 query=%s: %s", query, exc)
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
        logger.warning("博查搜索失败 query=%s: %s，回退到 cn.bing", query, exc)
        return _search_bing_cn(query)
