#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Langfuse token 成本核销：聚合最近 N 条 trace 内 GENERATION 的 token 用量（评审证据：成本账）

用法：
    export LANGFUSE_PUBLIC_KEY=pk-xxx LANGFUSE_SECRET_KEY=sk-xxx
    python scripts/langfuse_cost_audit.py [--limit-traces 20] [--since 2026-09-01]
输出 logs/langfuse_cost_audit.md；价格 --price-in/--price-out（默认 qwen3.7-flash 约值，按需覆盖）

注意（本机实测结论）：
- OTLP 导出认证用 **Basic base64(pk:sk)**，不是 Bearer（Langfuse OTel 端点实测 401 vs 200）。
- usage 只出现在单条 trace 详情（GET /api/public/traces/{id}），v2/observations 批量接口不暴露。
- 需要 spring-ai 观测把 token 用量挂成 gen_ai.usage.* 属性（见 ChatModelCompletionContentObservationFilter）。
安全：凭据只从环境变量读，不落盘、不打印。
"""
import argparse
import base64
import datetime as dt
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = "https://jp.cloud.langfuse.com/api/public"


def auth_header():
    pk = os.environ.get("LANGFUSE_PUBLIC_KEY")
    sk = os.environ.get("LANGFUSE_SECRET_KEY")
    if not pk or not sk:
        raise SystemExit("需要环境变量 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY")
    return "Basic " + base64.b64encode(f"{pk}:{sk}".encode()).decode()


def get(path, retries=2):
    req = urllib.request.Request(API + path, headers={"Authorization": auth_header()})
    for a in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            if a == retries:
                raise
            time.sleep(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit-traces", type=int, default=20)
    ap.add_argument("--since", default=None, help="只统计该时间之后的 trace，如 2026-09-01（本地日期）")
    ap.add_argument("--out", default=str(ROOT / "logs" / "langfuse_cost_audit.md"))
    ap.add_argument("--price-in", type=float, default=0.0012, help="元/千token（输入）")
    ap.add_argument("--price-out", type=float, default=0.0042, help="元/千token（输出）")
    args = ap.parse_args()

    traces = get(f"/traces?limit={args.limit_traces}")["data"]
    if args.since:
        since = dt.datetime(*map(int, args.since.split("-"))).strftime("%Y-%m-%d")
        traces = [t for t in traces if t["timestamp"][:10] >= since]
    print(f"[audit] traces 拉取 {len(traces)} 条（since={args.since or '全部'}）")

    gen_usage, span_n = [], {"span": 0, "gen": 0}
    for t in traces:
        try:
            detail = get(f"/traces/{t['id']}")
        except Exception as e:
            print(f"[audit] 跳过 trace {t['id'][:8]}: {e}")
            continue
        for o in detail.get("observations", []):
            if o.get("type") == "GENERATION":
                span_n["gen"] += 1
                u = o.get("usage") or {}
                i, oo = u.get("input") or 0, u.get("output") or 0
                if i or oo:
                    gen_usage.append((o.get("name") or "?", t["timestamp"], t.get("userId"), i, oo))
            elif o.get("type") == "SPAN":
                span_n["span"] += 1
        time.sleep(0.15)

    if not gen_usage:
        print("[audit] 无带 usage 的 GENERATION——先跑 LangfuseTraceSmokeLiveTest（需 OTLP 环境变量开启）")
        return 1

    total_in = sum(g[3] for g in gen_usage)
    total_out = sum(g[4] for g in gen_usage)
    cost = total_in / 1000 * args.price_in + total_out / 1000 * args.price_out
    by_name, by_user = {}, {}
    for name, ts, uid, i, o in gen_usage:
        by_name.setdefault(name, [0, 0, 0])
        by_name[name][0] += 1
        by_name[name][1] += i
        by_name[name][2] += o
        key = uid or "(nouser)"
        by_user.setdefault(key, [0, 0])
        by_user[key][0] += i
        by_user[key][1] += o

    lines = ["# Langfuse token 成本核销", "",
             f"- 窗口：最近 {len(traces)} 条 trace（since={args.since or '全部'}）",
             f"- GENERATION：{span_n['gen']} 个（含 usage {len(gen_usage)}）｜SPAN：{span_n['span']}",
             f"- **总 token：{total_in + total_out:,}（输入 {total_in:,} / 输出 {total_out:,}）**",
             f"- 估算成本（输入 ¥{args.price_in}/千tok / 输出 ¥{args.price_out}/千tok）：**¥{cost:.3f}**",
             "",
             "## 按观测名聚合", "",
             "| name | 次数 | 输入 tokens | 输出 tokens |", "|---|---|---|---|"]
    for name, (n, i, o) in sorted(by_name.items()):
        lines.append(f"| {name} | {n} | {i:,} | {o:,} |")
    if by_user:
        lines += ["", "## 按用户", ""]
        for uid, (i, o) in sorted(by_user.items()):
            lines.append(f"- {uid}：输入 {i:,} / 输出 {o:,}")
    lines += ["",
              "> 价格为本地估算（qwen3.7-flash 约值），正式账单以 DashScope 控制台为准；不含 image/embedding；"
              "trace 级明细见 Langfuse 界面。"]
    out = Path(args.out)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[audit] 已写入 {out}")


if __name__ == "__main__":
    sys.exit(main())