"""衣橱照片识别工具。

契约对齐 WeChatBot McpWardrobePhotoAnalyzer：
  入参: {"imageUrl": "https://signed.example.com/photo.png"}
  返回: {"summary":"...","candidates":[{...}, ...]}

数据源：百炼 qwen-vl-max 视觉模型（通过 OpenAI 兼容接口调用）。
需要 DASHSCOPE_API_KEY 环境变量。
"""

import base64
import json
import logging
import os

import httpx

logger = logging.getLogger(__name__)

# 视觉模型分析 prompt，引导模型返回结构化 JSON
_ANALYSIS_PROMPT = """你是一个专业的服装识别助手。请仔细分析图片中的服装单品，返回 JSON 格式的识别结果。

要求：
1. 如果图片展示的是一整套完整穿搭（例如上衣+裤子的套装，或包含鞋和配饰的整套造型），只返回一个代表整套的候选：
   - categoryCode 使用 OUTFIT
   - displayName 描述整套（如"深蓝色格子衬衫+黑色直筒长裤"）
   - 不要把一个套装拆成多件单品候选
2. 只有当图片中的多件衣物是相互独立、不成套的单件时，才分别返回候选（最多 8 件）
3. 每件单品返回以下字段：
   - displayName: 中文显示名（如"白色宽松T恤"）
   - categoryCode: 类目代码，从以下选择：OUTFIT/T_SHIRT/SHIRT/KNITWEAR/JACKET/JEANS/STRAIGHT_PANTS/SKIRT/DRESS/SHOES/BAG/ACCESSORY
   - colorPrimary: 主色代码（如 WHITE/BLACK/BLUE/RED/GREEN/GRAY/BROWN/BEIGE/PINK/YELLOW/ORANGE/PURPLE）
   - secondaryColors: 辅助色数组
   - styleTags: 风格标签数组（如 CASUAL/FORMAL/STREET/SPORTY/MINIMAL）
   - fitCode: 版型代码（如 REGULAR/RELAXED/SLIM/OVERSIZED）
   - seasonTags: 季节标签数组（如 SPRING/SUMMER/AUTUMN/WINTER）
   - attributes: 对象，含 patternCode（SOLID/STRIPES/CHECK/FLORAL）等
   - confidence: 识别置信度 0-1
   - qualityScore: 图片质量分 0-1（被遮挡或模糊时降低）
   - completenessStatus: READY 或 RETAKE_REQUIRED
   - retakeGuidance: 如果需要重拍，给出建议（否则空字符串）

请严格返回 JSON，不要包含 markdown 代码块标记或其他文字。
返回格式：
{"summary":"整体描述","candidates":[{...}]}
"""


def wardrobe_photo_analysis(imageUrl: str) -> str:
    """分析衣橱照片，返回结构化服装候选列表。

    Args:
        imageUrl: 图片的签名 URL，服务端自行下载。

    Returns:
        JSON 字符串，包含 summary 和 candidates 数组。
    """
    api_key = os.getenv("DASHSCOPE_API_KEY", "")
    if not api_key or api_key.startswith("sk-xxx"):
        return json.dumps({"error": "DASHSCOPE_API_KEY 未配置，无法调用视觉模型"},
                          ensure_ascii=False)

    try:
        # 下载图片并转 Base64
        image_b64 = _download_as_base64(imageUrl)

        # 调用百炼视觉模型
        base_url = os.getenv("DASHSCOPE_BASE_URL",
                             "https://dashscope.aliyuncs.com/compatible-mode/v1")
        model = os.getenv("VISION_MODEL", "qwen-vl-max")
        from openai import OpenAI
        client = OpenAI(api_key=api_key, base_url=base_url)

        response = client.chat.completions.create(
            model=model,
            messages=[{
                "role": "user",
                "content": [
                    {"type": "image_url",
                     "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}},
                    {"type": "text", "text": _ANALYSIS_PROMPT},
                ],
            }],
            max_tokens=2000,
        )

        raw = response.choices[0].message.content.strip()
        # 清理可能的 markdown 代码块标记
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()

        # 验证是有效 JSON
        parsed = json.loads(raw)
        return json.dumps(parsed, ensure_ascii=False)

    except json.JSONDecodeError:
        logger.warning("视觉模型返回非 JSON 格式")
        return json.dumps(
            {"summary": "视觉模型返回格式异常", "candidates": []},
            ensure_ascii=False)
    except Exception as exc:
        logger.warning("衣橱识别失败 imageUrl=%s: %s", imageUrl[:50], exc)
        return json.dumps({"error": f"识别失败: {exc}"}, ensure_ascii=False)


def _download_as_base64(url: str) -> str:
    """下载图片 URL 并返回 Base64 编码。"""
    resp = httpx.get(url, timeout=30, follow_redirects=True)
    resp.raise_for_status()
    return base64.b64encode(resp.content).decode("ascii")
