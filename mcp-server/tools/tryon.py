"""虚拟试衣工具 — 火山引擎 doubao-seedream-4.0 图像生成 API（唯一 Provider）。

契约对齐 WeChatBot McpVirtualTryOnService：
  入参: {"personImageUrl":"...","garmentImageUrl":"...","garmentCategory":"..."}
  返回: {"imageBase64":"..."} 或 {"imageUrl":"..."} 或 {"error":"..."}

实现方式：多图生图（图1=模特全身照，图2=服装单品图），prompt 要求把图2的服装
换到图1模特身上。图片先由本服务下载并转 Base64 传给火山引擎，避免签名 URL
（尤其本地存储）对公网不可达的问题。

环境变量：
  ARK_API_KEY   必填，火山方舟 API Key（https://console.volcengine.com/ark）
  ARK_MODEL     默认 doubao-seedream-4-0-250828（可改用推理接入点 ID）
  ARK_BASE_URL  默认 https://ark.cn-beijing.volces.com/api/v3
  ARK_SIZE      默认 2K（可选 1K/2K/4K）
  ARK_TIMEOUT   默认 120 秒
  ARK_RETRIES   默认 2（对瞬时错误指数退避重试）
"""

import base64
import json
import logging
import os
import time

import httpx

logger = logging.getLogger(__name__)

_ARK_BASE_URL = os.getenv("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3").rstrip("/")
_ARK_MODEL = os.getenv("ARK_MODEL", "doubao-seedream-4-0-250828")
_ARK_SIZE = os.getenv("ARK_SIZE", "2K")
_ARK_TIMEOUT = float(os.getenv("ARK_TIMEOUT", "120"))
_ARK_RETRIES = int(os.getenv("ARK_RETRIES", "2"))
_DOWNLOAD_TIMEOUT = 30.0
_GENERATE_ENDPOINT = f"{_ARK_BASE_URL}/images/generations"
# 衣物类目 → 提示词里的中文品类词
_CATEGORY_LABELS = {
    "FULL_OUTFIT": "整套穿搭",
    "T_SHIRT": "上衣",
    "SHIRT": "上衣",
    "TOP": "上衣",
    "OUTER": "外套",
    "SWEATER": "毛衣",
    "JACKET": "夹克",
    "HOODIE": "卫衣",
    "COAT": "大衣",
    "CARDIGAN": "开衫",
    "BLAZER": "西装外套",
    "JEANS": "牛仔裤",
    "PANTS": "长裤",
    "STRAIGHT_PANTS": "长裤",
    "SHORTS": "短裤",
    "SKIRT": "半身裙",
    "TROUSERS": "长裤",
    "LEGGINGS": "紧身裤",
    "DRESS": "连衣裙",
    "OVERALL": "连体裤",
    "JUMPSUIT": "连体裤",
    "ACCESSORY": "配饰",
    "SHOES": "鞋子",
    "BAG": "包",
}

_PROMPT_TEMPLATE = (
    "图1是模特的全身照，图2是{label}单品图。请把图2的{label}穿到图1模特身上，"
    "生成一张逼真的虚拟试穿效果图。保持模特的身份、面部特征、发型、姿态、光影和背景不变，"
    "服装自然贴合身体，褶皱、材质细节真实。输出保持图1的构图与宽高比。"
)

# 整套试穿：图2 是"上衣在上、下装在下"的穿搭拼图，一次把整套穿到模特身上
_FULL_OUTFIT_PROMPT = (
    "图1是模特的全身照，图2是一套完整穿搭的拼图（上方是上衣、下方是下装）。"
    "请把图2中的这套上衣和下装分别完整穿到图1模特身上，上衣穿在上半身、下装穿在下半身，"
    "保持上下装的搭配关系正确，生成一张逼真的整套虚拟试穿效果图。"
    "保持模特的身份、面部特征、发型、姿态、光影和背景不变，"
    "服装自然贴合身体，褶皱、材质细节真实。输出保持图1的构图与宽高比。"
)


def virtual_try_on(personImageUrl: str, garmentImageUrl: str,
                   garmentCategory: str = "") -> str:
    """将指定服装穿到人物模板图上，生成试穿效果图（火山引擎 Seedream-4.0）。

    Args:
        personImageUrl: 人物全身模板图的签名 URL。
        garmentImageUrl: 衣物展示图的签名 URL。
        garmentCategory: 衣物类目码（如 OUTER/T_SHIRT/JEANS）。

    Returns:
        JSON 字符串，含 imageBase64（结果图 Base64）或 error。
    """
    api_key = os.getenv("ARK_API_KEY", "").strip()
    if not api_key:
        return json.dumps(
            {"error": "ARK_API_KEY 未配置，请在 mcp-server/.env 中填写火山方舟 API Key"},
            ensure_ascii=False)

    if not personImageUrl or not garmentImageUrl:
        return json.dumps({"error": "试衣素材图片 URL 缺失"}, ensure_ascii=False)

    try:
        person_b64 = _download_to_base64(personImageUrl)
        garment_b64 = _download_to_base64(garmentImageUrl)
    except Exception as exc:
        logger.warning("试衣素材下载失败: %s", exc)
        return json.dumps({"error": f"试衣素材图片下载失败: {exc}"}, ensure_ascii=False)

    category = garmentCategory.upper()
    label = _CATEGORY_LABELS.get(category, "服装")
    prompt = _FULL_OUTFIT_PROMPT if category == "FULL_OUTFIT" else _PROMPT_TEMPLATE.format(label=label)

    body = {
        "model": _ARK_MODEL,
        "prompt": prompt,
        "image": [
            _data_url(person_b64),
            _data_url(garment_b64),
        ],
        "size": _ARK_SIZE,
        "sequential_image_generation": "disabled",
        "response_format": "b64_json",
        "watermark": False,
    }

    last_error = None
    for attempt in range(_ARK_RETRIES + 1):
        if attempt > 0:
            backoff = 5 * (2 ** (attempt - 1))
            logger.info("试衣请求第 %d 次重试，等待 %ss", attempt + 1, backoff)
            time.sleep(backoff)
        try:
            result = _call_generate(body)
            return result
        except RetryableError as exc:
            last_error = str(exc)
            logger.warning("试衣请求可重试失败(第%d次): %s", attempt + 1, last_error)
        except Exception as exc:
            # 非瞬时错误（鉴权、参数错误等）直接返回，不重试
            return json.dumps({"error": f"试衣失败: {exc}"}, ensure_ascii=False)

    return json.dumps({"error": f"试衣失败: {last_error}"}, ensure_ascii=False)


class RetryableError(Exception):
    """瞬时错误：连接失败、超时、限流（429）、服务端错误（5xx）。"""


def _call_generate(body: dict) -> str:
    api_key = os.getenv("ARK_API_KEY", "").strip()
    try:
        resp = httpx.post(
            _GENERATE_ENDPOINT,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {api_key}",
            },
            json=body,
            timeout=_ARK_TIMEOUT,
        )
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.ReadTimeout) as exc:
        raise RetryableError(f"无法连接火山引擎: {exc}") from exc
    except httpx.HTTPError as exc:
        raise RetryableError(f"请求火山引擎异常: {exc}") from exc

    if resp.status_code in (429, 500, 502, 503, 504):
        raise RetryableError(f"火山引擎返回 HTTP {resp.status_code}")

    if resp.status_code != 200:
        try:
            detail = resp.json()
            message = detail.get("error", {}).get("message", resp.text[:200])
        except Exception:
            message = resp.text[:200]
        return json.dumps({"error": f"试衣失败(HTTP {resp.status_code}): {message}"},
                          ensure_ascii=False)

    try:
        payload = resp.json()
    except Exception as exc:
        raise RetryableError(f"火山引擎响应解析失败: {exc}") from exc

    data = payload.get("data") or []
    for item in data:
        if isinstance(item, dict):
            if item.get("error"):
                message = item["error"].get("message", "未知错误")
                return json.dumps({"error": f"试衣生成失败: {message}"}, ensure_ascii=False)
            if item.get("b64_json"):
                return json.dumps({"imageBase64": item["b64_json"]}, ensure_ascii=False)
            if item.get("url"):
                return json.dumps({"imageUrl": item["url"]}, ensure_ascii=False)

    if payload.get("error"):
        message = payload["error"].get("message", str(payload["error"]))
        return json.dumps({"error": f"试衣失败: {message}"}, ensure_ascii=False)

    return json.dumps({"error": "火山引擎未返回有效图片"}, ensure_ascii=False)


def _download_to_base64(url: str) -> bytes:
    """下载图片内容（原始字节）。"""
    resp = httpx.get(url, timeout=_DOWNLOAD_TIMEOUT, follow_redirects=True)
    resp.raise_for_status()
    return resp.content


def _data_url(raw: bytes) -> str:
    """字节 → data URL（Seedream 图片格式支持 jpeg/png/webp/bmp/tiff/gif）。"""
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")
