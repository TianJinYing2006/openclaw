"""天气查询工具。

契约对齐 WeChatBot McpWeatherProvider：
  入参: {"city": "杭州"}
  返回: {"province":"浙江省","city":"杭州市","weather":"小雨","temperature":26,
         "windDirection":"西南风","windPower":"2级","humidity":95,"reportTime":"5分钟前发布"}

数据源：wttr.in 免费 API（无需 Key）。
"""

import json
import logging

import httpx

logger = logging.getLogger(__name__)


def get_weather(city: str) -> str:
    """查询指定城市的当前天气。

    Args:
        city: 城市名称（如 "杭州"、"北京市"），已由客户端去除前后空格。

    Returns:
        JSON 字符串，包含 province/city/weather/temperature/windDirection/
        windPower/humidity/reportTime 八个字段。
    """
    try:
        # wttr.in 免费 API，format=j1 返回结构化 JSON
        url = f"https://wttr.in/{city}"
        resp = httpx.get(url, params={"format": "j1"}, timeout=10,
                         headers={"Accept-Language": "zh-CN"})
        resp.raise_for_status()
        data = resp.json()

        current = data.get("current_condition", [{}])[0]
        area = data.get("nearest_area", [{}])[0]

        result = {
            "province": _value_of(area.get("region")),
            "city": city,
            "weather": _translate_desc(current.get("weatherDesc", [{}])[0].get("value", "")),
            "temperature": int(current["temp_C"]) if current.get("temp_C") else None,
            "windDirection": _translate_wind(current.get("winddir16Point", "")),
            "windPower": _beaufort_to_scale(current.get("windspeedKmph", "0")),
            "humidity": int(current["humidity"]) if current.get("humidity") else None,
            "reportTime": current.get("observation_time", ""),
        }
        return json.dumps(result, ensure_ascii=False)

    except Exception as exc:
        logger.warning("天气查询失败 city=%s: %s", city, exc)
        return json.dumps({"error": f"天气查询失败: {exc}"}, ensure_ascii=False)


def _value_of(items) -> str:
    """wttr.in 的 nearest_area 字段是 [{"value": "..."}] 结构，提取其中的字符串。"""
    if isinstance(items, list) and items:
        first = items[0]
        if isinstance(first, dict):
            return str(first.get("value", ""))
        return str(first)
    return "" if items is None else str(items)


def _translate_desc(desc: str) -> str:
    """wttr.in 英文天气描述转中文。"""
    mapping = {
        "Sunny": "晴", "Clear": "晴", "Partly cloudy": "多云",
        "Cloudy": "阴", "Overcast": "阴", "Mist": "薄雾", "Fog": "雾",
        "Light rain": "小雨", "Moderate rain": "中雨", "Heavy rain": "大雨",
        "Light snow": "小雪", "Moderate snow": "中雪", "Heavy snow": "大雪",
        "Thunderstorm": "雷阵雨", "Drizzle": "毛毛雨", "Rain": "雨",
        "Snow": "雪", "Rain shower": "阵雨",
    }
    return mapping.get(desc.strip(), desc)


def _translate_wind(direction: str) -> str:
    """风向英文转中文。"""
    mapping = {
        "N": "北风", "NNE": "北东北风", "NE": "东北风", "ENE": "东东北风",
        "E": "东风", "ESE": "东东南风", "SE": "东南风", "SSE": "南东南风",
        "S": "南风", "SSW": "南西南风", "SW": "西南风", "WSW": "西西南风",
        "W": "西风", "WNW": "西西北风", "NW": "西北风", "NNW": "北西北风",
    }
    return mapping.get(direction.strip(), direction)


def _beaufort_to_scale(kmph_str: str) -> str:
    """风速 km/h 转风力等级。"""
    try:
        kmph = int(kmph_str)
        if kmph < 1:
            return "0级"
        elif kmph < 6:
            return "1级"
        elif kmph < 12:
            return "2级"
        elif kmph < 20:
            return "3级"
        elif kmph < 29:
            return "4级"
        elif kmph < 39:
            return "5级"
        elif kmph < 50:
            return "6级"
        elif kmph < 62:
            return "7级"
        elif kmph < 75:
            return "8级"
        else:
            return "9级以上"
    except (ValueError, TypeError):
        return ""
