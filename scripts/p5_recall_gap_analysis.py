# -*- coding: utf-8 -*-
"""P5 失败模式分析：找出 gt 不在 top-50 候选池的 query，聚合场景/季节/正式度分布。

读取 logs/p5_rule_rerank_pools.tsv，对每行检查 gt 是否出现在 pool 列表里。
- 在 → 记 rank（0-indexed）
- 不在 → 这是"召回缺失"的失败案例，归入 14% 那一档

输出：① 8 条召回缺失的 query 明细；② 它们的 scene/season/styleHint/formality 分布；③ 对照"在池里但 top-5 外"的样本，找差异。
"""
import csv
import re
import sys
from collections import Counter
from pathlib import Path

POOL_TSV = Path(r"D:\项目\WeChatBot\logs\p5_rule_rerank_pools.tsv")
OUT_TXT = Path(r"D:\项目\WeChatBot\logs\p5_recall_gap_analysis.md")


def parse_pool(pool_str):
    """解析 '003:0.2998,034:0.2981,...' → [(id, score), ...] 保持顺序。"""
    items = []
    for token in (pool_str or "").split(","):
        token = token.strip()
        if not token or ":" not in token:
            continue
        oid, score = token.rsplit(":", 1)
        try:
            items.append((oid.strip(), float(score)))
        except ValueError:
            continue
    return items


def main():
    rows = []
    with POOL_TSV.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for r in reader:
            if not r.get("query"):
                continue
            rows.append(r)

    print(f"评测集: {len(rows)} 条 query")
    in_pool = []
    out_of_pool = []
    rank_buckets = {"0-4": 0, "5-9": 0, "10-19": 0, "20-49": 0, "MISS": 0}
    for r in rows:
        gt = (r.get("gt") or "").strip()
        pool = parse_pool(r.get("pool") or "")
        ids = [t[0] for t in pool]
        if gt in ids:
            rank = ids.index(gt)
            in_pool.append((r, rank, pool, ids))
            if rank <= 4:
                rank_buckets["0-4"] += 1
            elif rank <= 9:
                rank_buckets["5-9"] += 1
            elif rank <= 19:
                rank_buckets["10-19"] += 1
            else:
                rank_buckets["20-49"] += 1
        else:
            out_of_pool.append((r, pool, ids))
            rank_buckets["MISS"] += 1

    print(f"\n=== rank 分布（outfit 级，page_size=50）===")
    for k, v in rank_buckets.items():
        print(f"  {k:8s}: {v:3d}  ({100.0*v/len(rows):.1f}%)")

    print(f"\n=== 召回缺失（gt 不在 top-50）: {len(out_of_pool)} 条 ===")
    miss_scene = Counter()
    miss_season = Counter()
    miss_formality = Counter()
    miss_style = Counter()
    for r, pool, ids in out_of_pool:
        miss_scene[(r.get("scene") or "").strip()] += 1
        miss_season[(r.get("season") or "").strip()] += 1
        miss_formality[(r.get("formality") or "").strip()] += 1
        miss_style[(r.get("styleHint") or "").strip()] += 1
        print(f"  - query={r['query'][:55]:55s} | gt={r['gt']:>4s} | scene={r['scene']:14s} season={r['season']:8s} formality={r['formality']} style={r['styleHint']}")

    print(f"\n=== 召回缺失的 scene 分布 ===")
    for k, v in miss_scene.most_common():
        print(f"  {k:20s}: {v}")
    print(f"\n=== 召回缺失的 season 分布 ===")
    for k, v in miss_season.most_common():
        print(f"  {k:20s}: {v}")
    print(f"\n=== 召回缺失的 formality 分布 ===")
    for k, v in miss_formality.most_common():
        print(f"  formality={k:5s}: {v}")
    print(f"\n=== 召回缺失的 styleHint 分布 ===")
    for k, v in miss_style.most_common():
        print(f"  {k:20s}: {v}")

    # 对照组：在池里且 rank<=4 的样本，看它们的 scene/season 分布
    hit_count = sum(1 for _, rank, _, _ in in_pool if rank <= 4)
    print(f"\n=== 对照组：top-5 命中（rank 0-4）{hit_count} 条 ===")
    hit_scene = Counter()
    for r, rank, pool, ids in in_pool:
        if rank <= 4:
            hit_scene[(r.get("scene") or "").strip()] += 1
    for k, v in hit_scene.most_common():
        print(f"  {k:20s}: {v}")

    # 关键洞察：召回缺失的 query 是否有些 outfit ID 集中？比如某 gt 总是丢失
    miss_gt_counter = Counter(r["gt"] for r, _, _ in out_of_pool)
    print(f"\n=== 召回缺失的 gt outfit ID 频次 ===")
    for k, v in miss_gt_counter.most_common():
        print(f"  outfit {k}: {v} 次")

    # 写 markdown 报告
    lines = []
    lines.append("# P5 召回缺失失败模式分析\n")
    lines.append(f"评测集: {len(rows)} 条 query (outfit 级，page_size=50)\n")
    lines.append("\n## rank 分布\n")
    lines.append("| 区间 | 数量 | 占比 |")
    lines.append("|---|---|---|")
    for k, v in rank_buckets.items():
        lines.append(f"| {k} | {v} | {100.0*v/len(rows):.1f}% |")
    lines.append("\n## 召回缺失明细\n")
    lines.append("| query | gt | scene | season | formality | styleHint |")
    lines.append("|---|---|---|---|---|---|")
    for r, _, _ in out_of_pool:
        q = r["query"][:50].replace("|", "\\|")
        lines.append(f"| {q} | {r['gt']} | {r['scene']} | {r['season']} | {r['formality']} | {r['styleHint']} |")
    lines.append("\n## 召回缺失的 scene 分布\n")
    lines.append("| scene | 次数 |")
    lines.append("|---|---|")
    for k, v in miss_scene.most_common():
        lines.append(f"| {k} | {v} |")
    lines.append("\n## 召回缺失的 season 分布\n")
    lines.append("| season | 次数 |")
    lines.append("|---|---|")
    for k, v in miss_season.most_common():
        lines.append(f"| {k} | {v} |")
    lines.append("\n## 召回缺失的 gt outfit ID 频次\n")
    lines.append("| outfit | 次数 |")
    lines.append("|---|---|")
    for k, v in miss_gt_counter.most_common():
        lines.append(f"| {k} | {v} |")
    lines.append("\n## 对照：top-5 命中样本的 scene 分布\n")
    lines.append("| scene | 次数 |")
    lines.append("|---|---|")
    for k, v in hit_scene.most_common():
        lines.append(f"| {k} | {v} |")
    OUT_TXT.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告已写入: {OUT_TXT}")


if __name__ == "__main__":
    main()
