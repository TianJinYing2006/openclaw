"""服装抠图工具。

契约对齐 WeChatBot McpGarmentCutoutService：
  入参: {"sourceImageUrl":"...","displayName":"...","category":"...",
         "colorPrimary":"...","instruction":"..."}
  返回: {"imageBase64":"..."} 或 {"imageUrl":"..."} 或 {"error":"..."}

数据源：rembg 本地模型（无需 API Key，首次运行自动下载约 170MB 模型）。
garment_cutout 和 garment_revise 使用相同逻辑（rembg 不区分首次抠图和修订）。
"""

import base64
import io
import json
import logging

import httpx
from PIL import Image

logger = logging.getLogger(__name__)

# rembg 延迟导入，避免首次启动时长时间加载模型
_rembg_remove = None


def _get_remove_func():
    """延迟加载 rembg，首次调用时加载模型。"""
    global _rembg_remove
    if _rembg_remove is None:
        from rembg import remove
        _rembg_remove = remove
    return _rembg_remove


def garment_cutout(sourceImageUrl: str, displayName: str = "",
                   category: str = "", colorPrimary: str = "",
                   instruction: str = "") -> str:
    """从照片中提取服装单品并去除背景。

    Args:
        sourceImageUrl: 源图签名 URL，服务端自行下载。
        displayName: 候选显示名（如"白色T恤"），辅助理解。
        category: 标准类目（如 T_SHIRT、JEANS）。
        colorPrimary: 主色（如 WHITE）。
        instruction: 用户修改指令；为空表示忠实提取。

    Returns:
        JSON 字符串，含 imageBase64（PNG 透明背景图）或 error。
    """
    return _do_cutout(sourceImageUrl)


def garment_revise(sourceImageUrl: str, displayName: str = "",
                   category: str = "", colorPrimary: str = "",
                   instruction: str = "") -> str:
    """基于已有草稿图做局部修改。

    当前实现与 garment_cutout 相同（rembg 不区分语义）。
    替换为专业模型时，可在此实现真正的修订逻辑。

    Args:
        sourceImageUrl: 草稿图签名 URL。
        displayName: 候选显示名。
        category: 标准类目。
        colorPrimary: 主色。
        instruction: 用户修改指令（如"整体窄一点"）。

    Returns:
        JSON 字符串，含 imageBase64 或 error。
    """
    return _do_cutout(sourceImageUrl)


def _do_cutout(image_url: str) -> str:
    """执行抠图：下载图片 → rembg 去除背景 → 返回 Base64。"""
    try:
        # 下载源图
        resp = httpx.get(image_url, timeout=30, follow_redirects=True)
        resp.raise_for_status()
        source_bytes = resp.content

        # rembg 去除背景
        remove = _get_remove_func()
        cutout_bytes = remove(source_bytes)

        # 裁剪透明区域（去除多余空白边距）
        cutout_bytes = _trim_transparent(cutout_bytes)

        # 返回 Base64
        b64 = base64.b64encode(cutout_bytes).decode("ascii")
        return json.dumps({"imageBase64": b64}, ensure_ascii=False)

    except Exception as exc:
        logger.warning("抠图失败 url=%s: %s", image_url[:50], exc)
        return json.dumps({"error": f"抠图失败: {exc}"}, ensure_ascii=False)


def _trim_transparent(png_bytes: bytes) -> bytes:
    """裁剪 PNG 透明边距，减小图片体积。"""
    try:
        img = Image.open(io.BytesIO(png_bytes))
        bbox = img.getbbox()
        if bbox:
            img = img.crop(bbox)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except Exception:
        return png_bytes
