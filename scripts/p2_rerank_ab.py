# -*- coding: utf-8 -*-
"""P2 rerank AB：RAGFlow top-20 候选 vs DashScope qwen3-reranker 重排后 top-5。

不依赖 Spring 容器：直接调 RAGFlow retrieval API（v0.26，127.0.0.1:9380）与
DashScope text-rerank API。检索词复用 QueryAnalyzer 已持久化的分析结果
（fashion_query_analysis_cache，P1.5 固化），保证与 Java 评测完全同源。

输出：baseline vs rerank 的 top-1/top-5 对照、improved/regressed 逐条明细。
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:9380"
RF_KEY = "ragflow-ndA7j5jgyAgqxxIjI-25ENIizPTkfCorJOeAiRQsFFI"
DATASET_ID = "0424b4608d9211f1929d156e5467d734"

LOCAL_PROPS = r"D:\项目\WeChatBot\src\main\resources\application-local.properties"
EVAL_TSV = r"D:\项目\WeChatBot\..\..\..\..\tmp\eval_rerank.tsv"


def load_dashscope_key():
    with open(LOCAL_PROPS, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if line.startswith("DASHSCOPE_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("DASHSCOPE_API_KEY not found in local props")


def load_rows():
    # 评测数据上一导出到 logs/eval_rerank.tsv（57 行）
    # 实测：mysql client 输出为 UTF-8（bash 终端按 GBK 显示才乱码），按 UTF-8 解码
    path = r"D:\项目\WeChatBot\logs\eval_rerank.tsv"
    with open(path, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-8", errors="replace")
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        ui, gt, aq = parts[0], parts[1].strip(), parts[2]
        try:
            aqj = json.loads(aq)
        except Exception:
            continue
        rows.append((ui, gt, aqj))
    return rows


def normalize_outfit(raw):
    m = re.search(r"(\d+)", raw or "")
    if not m:
        return str(raw)
    try:
        return "%03d" % int(m.group(1))
    except Exception:
        return str(raw)


def build_search_question(aq):
    sb = []
    oq = aq.get("originalQuery") or ""
    if oq.strip():
        sb.append(oq)
    params = aq.get("params") or {}
    if params.get("scene"):
        sb.append("场景:" + params["scene"])
    if params.get("styleHint") and params["styleHint"].strip():
        sb.append("风格:" + params["styleHint"])
    if params.get("season"):
        sb.append("季节:" + params["season"])
    return " ".join(sb).strip()


def http_json(url, payload, token=None, timeout=30):
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ragflow_pool(question):
    body = {"question": question, "dataset_ids": [DATASET_ID], "page_size": 20,
            "similarity_threshold": 0.2, "vector_similarity_weight": 0.3, "rerank_id": ""}
    resp = http_json(BASE + "/api/v1/retrieval", body, token=RF_KEY)
    chunks = (resp.get("data") or {}).get("chunks") or []
    return chunks  # [{"document_keyword": "outfit_095.md", "content": "..."}, ...]


def dashscope_rerank(query, docs):
    key = load_dashscope_key()
    # 账号可用模型（2026-08-22 实测）：gte-rerank-v2 👌；qwen-rerank 需开通；qwen3-reranker 不存在
    payload = {"model": "gte-rerank-v2", "input": {"query": query, "documents": docs},
               "parameters": {"top_n": len(docs)}}
    url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    resp = http_json(url, payload, token=key, timeout=40)
    out = resp.get("output") or {}
    results = sorted(out.get("results") or [], key=lambda r: r.get("relevance_score", 0), reverse=True)
    return [r["index"] for r in results]


def main():
    rows = load_rows()
    print("样本量: %d 条（检索词来自 P1.5 固化分析，与 Java 评测同源）" % len(rows))

    total = len(rows)
    b_top1 = b_top5 = r_top1 = r_top5 = 0
    impr, regr, errs = [], [], []
    detail = []

    for i, (ui, gt, aq) in enumerate(rows, 1):
        sq = build_search_question(aq) or ui
        try:
            chunks = ragflow_pool(sq)
        except Exception as e:
            errs.append("%s | %s" % (ui, e))
            continue
        base_ids = [normalize_outfit(c.get("document_keyword")) for c in chunks]
        b1 = base_ids and gt == base_ids[0]
        b5 = gt in base_ids[:5]
        if b1:
            b_top1 += 1
        if b5:
            b_top5 += 1

        docs = [(c.get("content") or "")[:500] for c in chunks]
        try:
            order = dashscope_rerank(sq, docs)
        except Exception as e:
            errs.append("rerank %s | %s" % (ui, e))
            r_top5_cur = None
            order = []
        rk_ids = [base_ids[idx] for idx in order if idx < len(base_ids)]
        r1 = rk_ids and gt == rk_ids[0]
        r5 = gt in rk_ids[:5]
        if r5 is not None:
            if r1:
                r_top1 += 1
            if r5:
                r_top5 += 1
            if not b5 and r5:
                impr.append("%s(gt=%s 原rank%+d)" % (ui, gt, (base_ids.index(gt) + 1) if gt in base_ids else -1))
            elif b5 and not r5:
                regr.append("%s(gt=%s 原top5=%s)" % (ui, gt, base_ids[:5]))
        detail.append({"q": ui, "gt": gt, "base_top5": base_ids[:5], "rerank_top5": rk_ids[:5],
                       "b5": b5, "r5": r5})

    print("── 对照 ──")
    print("baseline(RAGFlow 原序): top-1 %d/%d = %.1f%%  top-5 %d/%d = %.1f%%" %
          (b_top1, total, 100.0 * b_top1 / total, b_top5, total, 100.0 * b_top5 / total))
    print("rerank(qwen3-reranker): top-1 %d/%d = %.1f%%  top-5 %d/%d = %.1f%%" %
          (r_top1, total, 100.0 * r_top1 / total, r_top5, total, 100.0 * r_top5 / total))
    print("救回: %d 条 | 退步: %d 条 | 错误: %d 条" % (len(impr), len(regr), len(errs)))
    if impr:
        print("── 救回明细 ──")
        for s in impr:
            print("  + " + s)
    if regr:
        print("── 退步明细 ──")
        for s in regr:
            print("  - " + s)
    if errs:
        print("── 错误 ──")
        for s in errs[:5]:
            print("  ERR " + s)

    with open(r"D:\项目\WeChatBot\logs\p2_rerank_ab_detail.json", "w", encoding="utf-8") as f:
        json.dump(detail, f, ensure_ascii=False, indent=1)
    print("明细已存 logs/p2_rerank_ab_detail.json")


if __name__ == "__main__":
    main()