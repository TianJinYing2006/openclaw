# -*- coding: utf-8 -*-
"""RAG 检索评测统一入口。

用法：
  python scripts/eval/run.py                    # 全变体对照（V1 线上口径 + V2/V3 消融）
  python scripts/eval/run.py --variants v1      # 只跑线上口径 V1
  python scripts/eval/run.py --data <tsv> --out <md>

前置条件：
  - RAGFLOW_API_KEY、RAGFLOW_DATASET_ID 环境变量（密钥走环境变量，禁止硬编码）
  - RAGFlow 已启动（默认 http://127.0.0.1:9380，可用 RAGFLOW_BASE_URL 覆盖）
  - 评测数据 logs/eval_rerank.tsv（含真实用户文本，不入库；导出方式见 README「RAG 检索评测」）
"""
import argparse
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from query_build import ALIASES, VARIANTS  # noqa: E402
from ragflow import ragflow_client          # noqa: E402
from metrics import compute                 # noqa: E402
from report import render                   # noqa: E402

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_TSV = os.path.join(PROJECT_ROOT, "logs", "eval_rerank.tsv")
DEFAULT_OUT = os.path.join(PROJECT_ROOT, "reports", "rag-eval-%Y%m%d.md")


def load_rows(path):
    if not os.path.exists(path):
        raise SystemExit(
            f"评测数据不存在: {path}\n"
            "来源：fashion_conversations 表（带 reference_outfit_id 的真实用户穿搭查询），"
            "导出为 UTF-8 TSV：<原始查询>\\t<GT编号>\\t<AnalyzedQuery JSON>。\n"
            "或通过 --data 指定其它文件。"
        )
    rows = []
    with open(path, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            rows.append((parts[0], parts[1].strip(), json.loads(parts[2])))
        except Exception:
            continue
    if not rows:
        raise SystemExit(f"未能从 {path} 解析出任何有效行")
    return rows


def main():
    ap = argparse.ArgumentParser(description="RAG 检索命中率评测（统一入口）")
    ap.add_argument("--data", default=DEFAULT_TSV, help="评测 TSV 路径（默认 logs/eval_rerank.tsv）")
    ap.add_argument("--out", default=None, help="Markdown 报告输出路径（默认 reports/rag-eval-日期.md）")
    ap.add_argument("--variants", default="v1,v2,v3",
                    help="变体：v1（线上口径）/v2（中文映射）/v3（子查询拼接），逗号分隔，默认全部")
    args = ap.parse_args()

    rows = load_rows(args.data)
    selected = [s.strip().lower() for s in args.variants.split(",") if s.strip()]
    variants = {}
    for key in selected:
        canonical = ALIASES.get(key, key)
        if canonical not in VARIANTS:
            print(f"未知变体: {key}，可用: {', '.join(ALIASES)}", file=sys.stderr)
            sys.exit(2)
        variants[canonical] = VARIANTS[canonical]

    client = ragflow_client()

    def retrieve(question):
        return client.retrieve_ids(question)

    result = compute(rows, retrieve, variants)

    out_path = args.out or datetime.datetime.now().strftime(DEFAULT_OUT)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    markdown = render({"base": client.base, "data_path": args.data}, result)

    print(markdown)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(markdown + "\n")
    print(f"\n报告已写入: {out_path}")


if __name__ == "__main__":
    main()