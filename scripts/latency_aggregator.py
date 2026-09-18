#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端延迟聚合（评审证据：性能，p50/p95 分路径）

数据源 : logs/judge_pairs.jsonl（JudgePairDataLiveTest 生成，行内含 graphElapsedMs——
         完整图管线 retrieve_memory→planner→rag→stylist→critic∥trend→coordinator 的端到端耗时）
输出   : logs/latency_summary.md

用法 :
    python scripts/latency_aggregator.py [--input logs/judge_pairs.jsonl]

注：简单请求只走 3 节点、复杂请求走 5-6 节点，两类耗时本就不同；
    本脚本按 query 意图粗分桶（试穿/天气/衣橱/穿搭推荐），并在报告标注样本量，
    避免把 n 很小的分组 p95 当作可靠结论。
"""
import argparse
import json
import re
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def intent_tag(q):
    if re.search(r"试穿|穿一下|上身效果|试试", q):
        return "试穿"
    if re.search(r"天气|降温|升温|高温|下雨", q):
        return "天气"
    if re.search(r"衣橱|衣柜|我的衣服|衣柜里", q):
        return "衣橱"
    return "穿搭推荐"


def pct(vals, p):
    if not vals:
        return None
    s = sorted(vals)
    k = max(0, min(len(s) - 1, int((p / 100) * len(s))))
    return s[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=str(ROOT / "logs" / "judge_pairs.jsonl"))
    args = ap.parse_args()
    inp = Path(args.input)
    if not inp.exists():
        raise SystemExit(f"找不到 {inp}——先跑 JudgePairDataLiveTest 生成时延样本")

    rows = [json.loads(l) for l in inp.read_text(encoding="utf-8").splitlines() if l.strip()]
    times = [r["graphElapsedMs"] for r in rows if r.get("graphElapsedMs") is not None]
    if not times:
        raise SystemExit("jsonl 中无 graphElapsedMs 字段")

    by_intent = {}
    degraded = sum(1 for r in rows if r.get("aDegraded", False))
    for r in rows:
        it = intent_tag(r["query"])
        if r.get("graphElapsedMs") is not None:
            by_intent.setdefault(it, []).append(r["graphElapsedMs"])

    lines = ["# 端到端延迟（图管线，真实 query，实测）", "",
             f"- 样本：{len(times)} 条真实 query（`fashion_conversations` 历史输入，P5 同源）",
             f"- 时间点：{inp.name} 生成批次（JudgePairDataLiveTest, shadow 模式）",
             "",
             "| 分组 | n | p50 | p90 | p95 | max |",
             "|---|---|---|---|---|---|"]
    for key, vals in [("总体", times)] + [(k, v) for k, v in sorted(by_intent.items())]:
        p50, p90, p95, mx = pct(vals, 50), pct(vals, 90), pct(vals, 95), max(vals)
        lines.append(f"| {key} | {len(vals)} | {p50}ms | {p90}ms | {p95}ms | {mx}ms |")
    lines += [
        "",
        f"- 图输出降级条数：{degraded}/{len(rows)}（降级不影响时延口径但影响质量口径，见 judge_summary.md）",
        "- 口径说明：graphElapsedMs 覆盖 retrieve_memory→planner→rag→stylist→critic∥trend→coordinator 全程；",
        "  不含微信链路/响应渲染，纯 Agent 管线耗时。历史口径（2026-08-04 n=6，仅单次 LLM 时延）见 quantitative-metrics.md。",
    ]
    out = ROOT / "logs" / "latency_summary.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[latency] 已写入 {out}")
    print("\n".join(lines))


if __name__ == "__main__":
    main()