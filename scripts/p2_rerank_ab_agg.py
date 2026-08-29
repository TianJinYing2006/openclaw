# -*- coding: utf-8 -*-
"""P2 rerank AB（改进版）：候选池 50 + outfit 聚合，判定 rerank 是"模型不行"还是"方式不对"。

对比三个口径：
  A. bl5chunk —— RAGFlow 原序前 5 chunk（= 生产现状）
  B. bl5agg   —— RAGFlow 原序按 outfit 聚合（每组取首次出现）后前 5 outfit
  C. rk5agg   —— gte-rerank-v2 对全部候选打分 → 按 outfit 取最高分 → 前 5 outfit

若 C 相比 B 仍大幅退步 → rerank 对本场景确实有害；若 C 回补 → 是"小池直接重排"方式的问题。
"""
import json
import os
import re
import sys
import urllib.request
import urllib.error

BASE = os.getenv("RAGFLOW_BASE_URL", "http://127.0.0.1:9380")
RF_KEY = os.getenv("RAGFLOW_API_KEY") or sys.exit("RAGFLOW_API_KEY 未设置（密钥走环境变量，禁止硬编码）")
DATASET_ID = os.getenv("RAGFLOW_DATASET_ID") or sys.exit("RAGFLOW_DATASET_ID 未设置")
LOCAL_PROPS = r"D:\项目\WeChatBot\src\main\resources\application-local.properties"
PAGE = 50
MAX_DOCS = 40  # DashScope rerank 单次 doc 上限（50 保险起见取 40）


def load_dashscope_key():
    with open(LOCAL_PROPS, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if line.startswith("DASHSCOPE_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("DASHSCOPE_API_KEY not found")


def load_rows():
    path = r"D:\项目\WeChatBot\logs\eval_rerank.tsv"
    text = open(path, "rb").read().decode("utf-8", errors="replace")
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        try:
            aqj = json.loads(parts[2])
        except Exception:
            continue
        rows.append((parts[0], parts[1].strip(), aqj))
    return rows


def normalize_outfit(raw):
    m = re.search(r"(\d+)", raw or "")
    if not m:
        return ""
    try:
        return "%03d" % int(m.group(1))
    except Exception:
        return ""


def build_search_question(aq):
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
    return " ".join(sb).strip()


def http_json(url, payload, token=None, timeout=40):
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ragflow_pool(question):
    body = {"question": question, "dataset_ids": [DATASET_ID], "page_size": PAGE,
            "similarity_threshold": 0.2, "vector_similarity_weight": 0.3, "rerank_id": ""}
    resp = http_json(BASE + "/api/v1/retrieval", body, token=RF_KEY)
    return (resp.get("data") or {}).get("chunks") or []


def dashscope_rerank(query, docs):
    key = load_dashscope_key()
    docs = docs[:MAX_DOCS]
    payload = {"model": "gte-rerank-v2", "input": {"query": query, "documents": docs},
               "parameters": {"top_n": len(docs)}}
    url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    resp = http_json(url, payload, token=key, timeout=60)
    out = resp.get("output") or {}
    results = sorted(out.get("results") or [], key=lambda r: r.get("relevance_score", 0), reverse=True)
    return results  # [{"index": i, "relevance_score": s}]


def agg_outfits(pairs):
    """pairs: [(outfit_id, score)]（score 越大越相关）→ 按 outfit 取最高分 → 按最高分降序取 id 列表。"""
    best = {}
    for oid, score in pairs:
        if not oid:
            continue
        if oid not in best or score > best[oid]:
            best[oid] = score
    ranked = sorted(best.items(), key=lambda kv: kv[1], reverse=True)
    return [oid for oid, _ in ranked]


def main():
    rows = load_rows()
    total = len(rows)
    print("样本量: %d 条 | 候选池 %d | rerank=gte-rerank-v2（outfit 聚合）" % (total, PAGE))
    bl_chunk = bl_agg = rk_agg = 0
    rk_imp, rk_reg, errs = [], [], []

    for i, (ui, gt, aq) in enumerate(rows, 1):
        sq = build_search_question(aq) or ui
        try:
            chunks = ragflow_pool(sq)
        except Exception as e:
            errs.append("%s | %s" % (ui, e))
            continue
        base_ids = [normalize_outfit(c.get("document_keyword")) for c in chunks]
        # A. 现状口径（chunk 序前 5）
        bl_chunk += (gt in base_ids[:5])
        # B. RAGFlow 原序 outfit 聚合（首次出现顺序 = score 递减）
        first_seen = []
        for oid in base_ids:
            if oid and oid not in first_seen:
                first_seen.append(oid)
        bl_agg += (gt in first_seen[:5])

        # C. rerank：打分（索引权重 = 原始序，score = relevance）
        docs = [(c.get("content") or "")[:500] for c in chunks[:MAX_DOCS]]
        try:
            results = dashscope_rerank(sq, docs)
        except Exception as e:
            errs.append("rerank %s | %s" % (ui, e))
            continue
        pairs = []
        for r in results:
            idx = r["index"]
            if idx < len(base_ids):
                pairs.append((base_ids[idx], r.get("relevance_score", 0)))
        rk_ids = agg_outfits(pairs)
        hit_c = gt in rk_ids[:5]
        rk_agg += hit_c
        if not (gt in first_seen[:5]) and hit_c:
            rk_imp.append("%s(gt=%s)" % (ui[:24], gt))
        elif (gt in first_seen[:5]) and not hit_c:
            rk_reg.append("%s(gt=%s)" % (ui[:24], gt))

    print("── 对照（top-5 命中）──")
    print("A. RAGFlow 原序前5 chunk : %d/%d = %.1f%%" % (bl_chunk, total, 100.0 * bl_chunk / total))
    print("B. RAGFlow 原序+outfit聚合: %d/%d = %.1f%%" % (bl_agg, total, 100.0 * bl_agg / total))
    print("C. rerank+outfit聚合      : %d/%d = %.1f%%" % (rk_agg, total, 100.0 * rk_agg / total))
    print("rerank vs B: 救回 %d 条 | 退步 %d 条 | 错误 %d 条" % (len(rk_imp), len(rk_reg), len(errs)))
    for s in rk_imp[:12]:
        print("  + " + s)
    for s in rk_reg[:12]:
        print("  - " + s)
    if errs:
        print("ERR sample:", errs[:3])


if __name__ == "__main__":
    main()