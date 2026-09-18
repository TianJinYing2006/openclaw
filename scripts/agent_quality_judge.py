#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent 质量评审：LLM-as-judge 成对对比（完整图管线 A vs 裸基线 B）

输入 : logs/judge_pairs.jsonl（JudgePairDataLiveTest 生成，每行含 query/planA/planB/降级标记/时延）
输出 : logs/judge_results.jsonl（逐条裁决）+ logs/judge_summary.md（汇总报告）
用法 :
    python scripts/agent_quality_judge.py                 # 全部
    python scripts/agent_quality_judge.py --limit 10      # 前 10 条（小成本试跑）
    python scripts/agent_quality_judge.py --resume        # 跳过已裁决的 query（幂等续跑）

Key 来源优先级：环境变量 DASHSCOPE_API_KEY > application-local.properties（本地开发默认）。
Judge 模型默认 qwen3.7-plus（评审质量优先），可 --model 覆盖。
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = ROOT / "logs" / "judge_pairs.jsonl"
DEFAULT_OUTPUT = ROOT / "logs" / "judge_results.jsonl"
SUMMARY = ROOT / "logs" / "judge_summary.md"
BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

JUDGE_SYSTEM = (
    "你是资深服装搭配评审。用户提出穿搭需求，系统给出两个候选方案：\n"
    "方案A：由多 Agent 完整管线生成（含知识检索与评审精炼）；\n"
    "方案B：由大模型直接作答（无检索、无精炼）。\n"
    "请从以下维度评判哪个方案更符合用户需求：\n"
    "1) 贴合度：是否回应了用户提到的场景/季节/风格/天气等约束；\n"
    "2) 可执行性：单品描述是否具体、搭配是否成体系（上装/下装/鞋/配饰）；\n"
    "3) 专业性：理由是否合理、是否避免空话套话；\n"
    "4) 信息量：是否给出了足够的参考（款式、颜色、理由、建议）。\n"
    "只输出严格 JSON：{\"winner\":\"A\"|\"B\"|\"tie\",\"reason\":\"一句话理由\"}\n"
    "双方旗鼓相当时选 tie，不要勉强选边。"
)


def load_key():
    env = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("SPRING_AI_API_KEY") or os.environ.get("AI_API_KEY")
    if env:
        return env
    props = ROOT / "src" / "main" / "resources" / "application-local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            m = re.match(r"\s*DASHSCOPE_API_KEY\s*=\s*(\S+)", line)
            if m:
                return m.group(1)
    raise SystemExit("未找到 DASHSCOPE_API_KEY：设置环境变量或确认 application-local.properties 存在")


def chat(model, key, user):
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": JUDGE_SYSTEM}, {"role": "user", "content": user}],
        "temperature": 0.0,
        "response_format": {"type": "json_object"},
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE_URL, data=body,
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def parse_verdict(raw):
    """容忍 ```json 包裹 / 多行 JSON，取 winner 与 reason；失败返回 None。"""
    for s in re.findall(r"\{.*?\}", raw, re.S):
        try:
            d = json.loads(s)
        except Exception:
            continue
        if "winner" in d:
            reason = str(d.get("reason", ""))[:200]
            return d["winner"] if d["winner"] in ("A", "B", "tie") else None, reason
    return None, None


def intent_tag(q):
    if re.search(r"试穿|穿一下|上身效果|试试", q):
        return "试穿"
    if re.search(r"天气|降温|升温|高温|下雨", q):
        return "天气"
    if re.search(r"衣橱|衣柜|我的衣服|衣柜里", q):
        return "衣橱"
    return "穿搭推荐"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=str(DEFAULT_INPUT))
    ap.add_argument("--output", default=str(DEFAULT_OUTPUT))
    ap.add_argument("--limit", type=int, default=0, help="0=全部")
    ap.add_argument("--model", default="qwen3.7-plus")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--summary", default=str(ROOT / "logs" / "judge_summary.md"),
                    help="汇总输出路径（子集实验务必指定，避免覆盖全局报告）")
    args = ap.parse_args()
    globals()["SUMMARY"] = Path(args.summary)

    key = load_key()
    in_path, out_path = Path(args.input), Path(args.output)
    if not in_path.exists():
        raise SystemExit(f"找不到输入 {in_path}——先跑 JudgePairDataLiveTest 生成")

    rows = [json.loads(l) for l in in_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    done = set()
    if args.resume and out_path.exists():
        for l in out_path.read_text(encoding="utf-8").splitlines():
            if l.strip():
                done.add(json.loads(l)["query"])
    todo = [r for r in rows if r["query"] not in done][: args.limit] if args.limit else \
            [r for r in rows if r["query"] not in done]
    print(f"[judge] 共 {len(rows)} 条，待裁决 {len(todo)} 条（resume={args.resume} limit={args.limit or 'all'}），模型={args.model}")
    if not todo:
        # 全量已裁决：从结果文件重生成汇总（支持修脚本后 --resume 刷新报告）
        existing = [json.loads(l) for l in out_path.read_text(encoding="utf-8").splitlines() if l.strip()] \
            if out_path.exists() else []
        if existing:
            write_summary(existing, args.model)
            print(f"[judge] 无待裁决项，已按现有 {len(existing)} 条结果刷新汇总")
        else:
            print("[judge] 无待裁决项且无结果文件，退出")
        return

    out_path.parent.mkdir(parents=True, exist_ok=True)
    agg = {"A": 0, "B": 0, "tie": 0, "degradedA": 0, "fail": 0}
    fail_reason = []
    judged = []
    with out_path.open("a", encoding="utf-8") as w:
        for i, r in enumerate(todo, 1):
            user = f"用户需求：{r['query']}\n\n方案A：\n{r.get('planA', '')}\n\n方案B：\n{r.get('planB', '')}"
            try:
                raw = chat(args.model, key, user)
                verdict, reason = parse_verdict(raw)
            except Exception as e:
                verdict, reason, raw = None, str(e), ""
            rec = {"query": r["query"], "intent": intent_tag(r["query"]),
                   "winner": verdict, "reason": reason,
                   "aDegraded": r.get("aDegraded", False), "graphElapsedMs": r.get("graphElapsedMs")}
            judged.append(rec)
            w.write(json.dumps(rec, ensure_ascii=False) + "\n")
            w.flush()

            if verdict is None:
                agg["fail"] += 1
                fail_reason.append((r["query"][:30], raw[:80] or reason))
            else:
                agg[verdict] += 1
            if r.get("aDegraded", False):
                agg["degradedA"] += 1
            print(f"[judge] {i}/{len(todo)} winner={verdict} | {r['query'][:36]}...")
            time.sleep(0.3)

    write_summary(judged, args.model)

def write_summary(records, model):
    """从裁决记录（含 winner/intent）生成汇总；records 为空则跳过。"""
    if not records:
        return
    agg = {"A": 0, "B": 0, "tie": 0, "degradedA": 0, "fail": 0}
    fail_reason = []
    by_intent = {}
    for rec in records:
        it = rec.get("intent") or intent_tag(rec["query"])
        rec["intent"] = it
        by_intent.setdefault(it, [0, 0, 0])  # A B tie
        w_ = rec.get("winner")
        if w_ is None:
            agg["fail"] += 1
            continue
        agg[w_] += 1
        if w_ in ("A", "B", "tie"):
            by_intent[it][0 if w_ == "A" else 1 if w_ == "B" else 2] += 1
        if rec.get("aDegraded", False):
            agg["degradedA"] += 1

    n = sum(agg[k] for k in ("A", "B", "tie"))
    lines = ["# Agent 质量评审（LLM-as-judge）", "",
             f"- 模型：{model}｜样本：{len(records)} 条真实 query（n={n} 有效裁决）",
             f"- **方案A（完整图管线）胜率：{100 * agg['A'] / n:.1f}%（{agg['A']}/{n}）**",
             f"- 方案B（裸基线）胜率：{100 * agg['B'] / n:.1f}%（{agg['B']}/{n}）",
             f"- 平局：{100 * agg['tie'] / n:.1f}%（{agg['tie']}/{n}）",
             f"- 方案A 降级数：{agg['degradedA']}｜裁决失败数：{agg['fail']}",
             "",
             "## 意图分布", ""]
    for it, (a, b, t) in sorted(by_intent.items()):
        s = a + b + t
        lines.append(f"- {it}：A {a} / B {b} / tie {t}（n={s}）")
    if fail_reason:
        lines += ["", "## 裁决失败样例", ""] + [f"- `{q}` → {raw}" for q, raw in fail_reason[:5]]
    SUMMARY.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[judge] 汇总已写入 {SUMMARY}")


if __name__ == "__main__":
    main()