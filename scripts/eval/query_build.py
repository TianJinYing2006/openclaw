# -*- coding: utf-8 -*-
"""检索词构造（评测用），与线上 QueryAnalyzer 输出对齐。

V1 现状 = originalQuery + 场景/风格/季节标签 —— 已被 A/B 证明为最优（参见
docs/rag-benchmark-design.md），线上检索即此口径。V2/V3 仅用于消融对照。
"""
import re

SCENE_CN = {"WORKPLACE": "通勤", "COMMUTE": "通勤", "FORMAL_EVENT": "正式场合",
            "SCHOOL": "校园", "TRAVEL": "旅行", "OUTDOOR": "户外", "DAILY": "日常"}
SEASON_CN = {"SPRING": "春天", "SUMMER": "夏天", "AUTUMN": "秋天", "WINTER": "冬天"}


def normalize_outfit_id(raw):
    m = re.search(r"(\d+)", raw or "")
    if not m:
        return ""
    return "%03d" % int(m.group(1))


def v1(aq, ui):
    sb = []
    oq = (aq.get("originalQuery") or "").strip()
    if oq:
        sb.append(oq)
    p = aq.get("params") or {}
    if p.get("scene"):
        sb.append("场景:" + p["scene"])
    if p.get("styleHint") and p["styleHint"].strip():
        sb.append("风格:" + p["styleHint"])
    if p.get("season"):
        sb.append("季节:" + p["season"])
    return " ".join(sb).strip() or ui


def v2(aq, ui):
    sb = []
    oq = (aq.get("originalQuery") or "").strip()
    if oq:
        sb.append(oq)
    p = aq.get("params") or {}
    sc = p.get("scene")
    if sc:
        sb.append(SCENE_CN.get(sc, sc))
    if p.get("styleHint") and p["styleHint"].strip():
        sb.append(p["styleHint"])
    se = p.get("season")
    if se:
        sb.append(SEASON_CN.get(se, se))
    return " ".join(sb).strip() or ui


def v3(aq, ui):
    dq = aq.get("decomposedQueries") or []
    if dq:
        return " ".join(dq).strip()
    return v2(aq, ui)


def merge_round_robin(pools, limit=20):
    seen, seen_set = [], set()
    max_len = max((len(x) for x in pools), default=0)
    for i in range(max_len):
        for pool in pools:
            if i < len(pool):
                item = pool[i]
                if item not in seen_set:
                    seen_set.add(item)
                    seen.append(item)
    return seen[:limit]


VARIANTS = {
    "V1 现状(英文枚举)": v1,
    "V2 中文映射": v2,
    "V3 子查询拼接": v3,
}

ALIASES = {"v1": "V1 现状(英文枚举)", "v2": "V2 中文映射", "v3": "V3 子查询拼接"}