#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 标准指标核算（对齐工业汇报口径：Recall@k / MRR / NDCG@k）

输入 : logs/p5_rule_rerank_pools.tsv（query|gt|scene|season|styleHint|formality|pool[50 条 id:score]）
输出 : logs/rag_standard_metrics.md + 控制台表格
说明 : 相关性为单 gt 二值标注 → NDCG 退化为"gt 排位折损"（1/log2(rank+1)），
       这是 retrieval 单标注 benchmark 的标准做法；同时给 recall 的标准误（诚实标注小样本置信度）。
用法 : python scripts/rag_standard_metrics.py
"""
import argparse
import math
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TSV = ROOT / "logs" / "p5_rule_rerank_pools.tsv"
OUT = ROOT / "logs" / "rag_standard_metrics.md"


def norm_id(raw):
    s = str(raw).replace("ref_", "").replace("outfit_", "").replace("[", "") \
        .replace("]", "").replace(".md", "").strip()
    try:
        return f"{int(s):03d}"
    except ValueError:
        return s


def parse_pool(pool_str):
    items = []
    for tok in pool_str.split(","):
        tok = tok.strip()
        if not tok or ":" not in tok:
            continue
        oid, _, score = tok.partition(":")
        items.append((norm_id(oid), float(score)))
    return items


def dcg_at_rank(rank, k):
    """单 gt 二值相关：仅当 rank<=k 时贡献 1/log2(rank+1)。"""
    if rank is None or rank > k:
        return 0.0
    return 1.0 / math.log2(rank + 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tsv", default=str(DEFAULT_TSV))
    args = ap.parse_args()

    rows = []
    with open(args.tsv, encoding="utf-8") as f:
        f.readline()  # header
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 7:
                continue
            rows.append(dict(query=parts[0], gt=norm_id(parts[1]), pool=parse_pool(parts[6])))

    n = len(rows)
    print(f"query 数: {n}")
    ranks, hit = [], []
    for r in rows:
        ids = [oid for oid, _ in r["pool"]]
        pos = ids.index(r["gt"]) if r["gt"] in ids else None
        if pos is not None:
            ranks.append(pos + 1)
            hit.append(1)
        else:
            hit.append(0)

    def recall(k):
        return sum(1 for rr, h in zip(ranks, hit) if h and rr <= k) / n

    def se(p, n_):
        return math.sqrt(p * (1 - p) / n_) if n_ else 0.0

    ks = [1, 5, 10, 20]
    stats = {k: (recall(k), se(recall(k), n)) for k in ks}
    pool_cov = sum(hit) / n
    mrr = sum(1.0 / rr for rr, h in zip(ranks, hit) if h) / n
    ndcg5 = sum(dcg_at_rank(rr, 5) for rr, h in zip(ranks, hit) if h) / n
    ndcg10 = sum(dcg_at_rank(rr, 10) for rr, h in zip(ranks, hit) if h) / n
    avg_rank = statistics.mean(ranks) if ranks else float("nan")

    lines = ["# RAG 标准指标（工业汇报口径）", "",
             f"- 样本：{n} 条真实 query（fashion_conversations 历史；单 gt 二值标注）",
             f"- 候选池：50 条/query（RAGFlow 语义召回上限）",
             f"- 召回侧覆盖（gt 进池率）= Recall@50 = **{pool_cov * 100:.1f}%**（{sum(hit)}/{n}）",
             "",
             "| 指标 | 数值 | ±SE（小样本诚实标注） |",
             "|---|---|---|"]
    for k in ks:
        p, e = stats[k]
        lines.append(f"| Recall@{k} | {p * 100:.2f}% | ±{e * 100:.2f}pp |")
    lines += [
        f"| **MRR** | **{mrr:.4f}** | — |",
        f"| **NDCG@5** | **{ndcg5:.4f}** | — |",
        f"| **NDCG@10** | **{ndcg10:.4f}** | — |",
        f"| 命中 query 平均 rank | {avg_rank:.1f} | — |",
        "",
        "### 口径与诚实声明",
        "- NDCG 为单 gt 二值相关折扣（1/log2(rank+1)），非多级相关性（工业多标注场景才有全量 NDCG）；",
        "- Recall 标准误按二项分布 sqrt(p(1-p)/n) 估算——n=60 时 ±5-6pp，**这是小样本置信度的真实边界**；",
        "- 结论定位：指标证明的是**评估方法可对齐工业口径**，不是业务效果；规模化指标需更大语料（方法论 scale 无关）。",
        "",
        "### 与守门口径的关系",
        "- 本表所有指标基于【语义原始序】（候选池检索顺序），Recall@5=23.33% 即【无规则重排 baseline】；",
        "- 规则重排后 top-5=36.67%-37.29%（P5 守门口径，见 docs/quantitative-metrics.md）——重排整体把",
        "  Recall@5 抬升 ~13pp，MRR/NDCG 同理应高于本表（本表未叠加重排，是【检索侧天花板】视角）。",
    ]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines)[:900])
    print(f"\n[metrics] 已写入 {OUT}")


if __name__ == "__main__":
    main()