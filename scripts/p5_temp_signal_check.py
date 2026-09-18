#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
温度感知重排的离线可行性核查（评审证据：先验证再动手，吸取"颜色信号"教训）

输入 : logs/p5_rule_rerank_pools.tsv（query|gt|scene|season|styleHint|formality|pool）
输出 : 控制台表格：每条天气 query 的 gt 文档是否存在"高温适配"信号
结论判断 :
  - gt 本身是短袖/轻薄/透气（温度匹配）→ 检索已召回正确目标，短板在 stylist 选用/排序，重排杠杆存疑；
  - gt 是长袖/厚重 → gt 违反显式温度约束，温度重排会自压 gt（与颜色信号同构死路）。
用法 : python scripts/p5_temp_signal_check.py [--pool logs/p5_rule_rerank_pools.tsv]
"""
import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "data" / "fashion_docs"

COOL_TOP = ["短袖", "无袖", "吊带", "背心", "T恤", "防晒衫"]
HOT_TOP = ["长袖", "毛衣", "针织衫", "卫衣", "西装外套", "夹克", "大衣"]
THICK = ["HEAVY", "MEDIUM_THICK"]


def read_doc(outfit_id):
    p = DOCS / f"outfit_{outfit_id}.md"
    return p.read_text(encoding="utf-8") if p.exists() else None


def temp_profile(outfit_id):
    doc = read_doc(str(outfit_id).zfill(3))
    if not doc:
        return None
    top = ""
    m = re.search(r"组成：上衣\(([^)]*)\)", doc)
    if m:
        top = m.group(1)
    season = ""
    m2 = re.search(r"适合季节：([^\n]+)", doc)
    if m2:
        season = m2.group(1)
    thick = ""
    m3 = re.search(r"thickness=([A-Z_]+)", doc)
    if m3:
        thick = m3.group(1)
    return {
        "top": top,
        "season": season,
        "thickness": thick,
        "cool": any(k in top for k in COOL_TOP) and not any(k in top for k in HOT_TOP),
        "hot": any(k in top for k in HOT_TOP),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pool", default=str(ROOT / "logs" / "p5_rule_rerank_pools.tsv"))
    args = ap.parse_args()

    rows = []
    with open(args.pool, encoding="utf-8") as f:
        header = f.readline()
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 6:
                rows.append(dict(query=parts[0], gt=parts[1], scene=parts[2],
                                  season=parts[3], style=parts[4], formality=parts[5]))
    weather = [r for r in rows if re.search(r"天气|高温|降温|升温|下雨|[0-9]{2}\s*度|℃", r["query"])]
    print(f"总 query {len(rows)}，天气类 {len(weather)} 条\n")
    print(f"{'gt':<6}{'上衣':<28}{'季节':<16}{'厚度':<12}判定")
    cool_cnt = hot_cnt = unknown = 0
    for r in weather:
        prof = temp_profile(r["gt"])
        if prof is None:
            print(f"{r['gt']:<6}文档缺失")
            unknown += 1
            continue
        verdict = "温度匹配(短袖/轻薄)" if prof["cool"] else ("违反温度约束(长袖/厚重)" if prof["hot"] else "中性/存疑")
        if prof["cool"]:
            cool_cnt += 1
        elif prof["hot"]:
            hot_cnt += 1
        print(f"{r['gt']:<6}{prof['top'][:26]:<28}{prof['season'][:14]:<16}{prof['thickness']:<12}{verdict}  | {r['query'][:20]}")
    print(f"\n结论：天气 query gt 温度匹配 {cool_cnt} / 违反 {hot_cnt} / 存疑缺失 {unknown}")

    if hot_cnt >= cool_cnt and hot_cnt > 0:
        print("→ gt 本身违反显式温度约束的比例高：温度感知重排会自压 gt（与颜色信号同构），判定为死路，不做。")
    elif cool_cnt > 0 and cool_cnt >= hot_cnt:
        print("→ gt 多为温度匹配条目：检索本就召回正确目标，短板在 stylist 从池中选用（已在天气单变量实验定位），重排杠杆存疑；建议先跑 P5 守门看 rerank 是否已含温度命中。")


if __name__ == "__main__":
    main()