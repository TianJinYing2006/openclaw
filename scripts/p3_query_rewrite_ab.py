# -*- coding: utf-8 -*-
"""P3 查询改写消融：V1~V4 变体 top-1/top-5/top-20 对照。

复刻 ResumeBenchmarkLiveTest.queryRewriteComparison 的口径（检索词来自
P1.5 固化分析，每查询仅用一份 AnalyzedQuery，消除 LLM 波动；RAGFlow 本地检索，
无需外网）：
  V1 现状 : originalQuery + "场景:英文枚举 风格:xx 季节:英文枚举"
  V2 中文映射: originalQuery + 场景/季节中文映射 + styleHint
  V3 子查询拼接: decomposedQueries join(" ")
  V4 多路合并: round-robin 去重合并 [V1, V2, V3, rawQuery] 四路 top-20
"""
import json
import os
import re
import sys
import urllib.request

BASE = os.getenv("RAGFLOW_BASE_URL", "http://127.0.0.1:9380")
RF_KEY = os.getenv("RAGFLOW_API_KEY") or sys.exit("RAGFLOW_API_KEY 未设置（密钥走环境变量，禁止硬编码）")
DATASET_ID = os.getenv("RAGFLOW_DATASET_ID") or sys.exit("RAGFLOW_DATASET_ID 未设置")

SCENE_CN = {"WORKPLACE": "通勤", "COMMUTE": "通勤", "FORMAL_EVENT": "正式场合",
            "SCHOOL": "校园", "TRAVEL": "旅行", "OUTDOOR": "户外", "DAILY": "日常"}
SEASON_CN = {"SPRING": "春天", "SUMMER": "夏天", "AUTUMN": "秋天", "WINTER": "冬天"}


def load_rows():
    text = open(r"D:\项目\WeChatBot\logs\eval_rerank.tsv", "rb").read().decode("utf-8", errors="replace")
    rows = []
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
    return rows


def norm(raw):
    m = re.search(r"(\d+)", raw or "")
    if not m:
        return ""
    try:
        return "%03d" % int(m.group(1))
    except Exception:
        return ""


def http_json(url, payload, timeout=25):
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json",
                                          "Authorization": "Bearer " + RF_KEY})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def retrieve_ids(question):
    body = {"question": question, "dataset_ids": [DATASET_ID], "page_size": 20,
            "similarity_threshold": 0.2, "vector_similarity_weight": 0.3, "rerank_id": ""}
    resp = http_json(BASE + "/api/v1/retrieval", body)
    return [norm(c.get("document_keyword")) for c in (resp.get("data") or {}).get("chunks") or []]


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
    seen = []
    seen_set = set()
    max_len = max((len(x) for x in pools), default=0)
    for i in range(max_len):
        for pool in pools:
            if i < len(pool):
                item = pool[i]
                if item not in seen_set:
                    seen_set.add(item)
                    seen.append(item)
    return seen[:limit]


def main():
    rows = load_rows()
    total = len(rows)
    print("样本量: %d 条（检索词来自 P1.5 固化分析；RAGFlow 本地检索，无需外网）" % total)
    names = ["V1 现状(英文枚举)", "V2 中文映射", "V3 子查询拼接", "V4 多路合并"]
    top1 = [0] * 4
    top5 = [0] * 4
    top20 = [0] * 4
    imp_v4, reg_v4, errs = [], [], []

    for ui, gt, aq in rows:
        raw = (aq.get("originalQuery") or "").strip() or ui
        sqs = [v1(aq, ui), v2(aq, ui), v3(aq, ui)]
        pools = []
        ok = True
        for sq in sqs:
            try:
                pools.append(retrieve_ids(sq))
            except Exception as e:
                errs.append("%s | %s" % (ui, e))
                ok = False
                break
        if not ok:
            continue
        try:
            pools.append(retrieve_ids(raw))
        except Exception as e:
            errs.append("%s | %s" % (ui, e))
            continue
        merged = merge_round_robin(pools, 20)
        all_pools = [pools[0], pools[1], pools[2], merged]
        for i in range(4):
            ids = all_pools[i]
            if ids and gt == ids[0]:
                top1[i] += 1
            if gt in ids[:5]:
                top5[i] += 1
            if gt in ids:
                top20[i] += 1
        if not (gt in all_pools[0][:5]) and (gt in merged[:5]):
            imp_v4.append("%s(gt=%s)" % (ui[:22], gt))
        elif (gt in all_pools[0][:5]) and not (gt in merged[:5]):
            reg_v4.append("%s(gt=%s)" % (ui[:22], gt))

    print("── top-1 ──")
    for i in range(4):
        print("  %-18s top-1: %2d/%d = %5.1f%%" % (names[i], top1[i], total, 100.0 * top1[i] / total))
    print("── top-5 ──")
    for i in range(4):
        print("  %-18s top-5: %2d/%d = %5.1f%%" % (names[i], top5[i], total, 100.0 * top5[i] / total))
    print("── top-20 覆盖 ──")
    for i in range(4):
        print("  %-18s top-20: %2d/%d = %5.1f%%" % (names[i], top20[i], total, 100.0 * top20[i] / total))
    print("── V4 vs V1(top-5 翻转) ──")
    print("  救回 %d 条 | 退步 %d 条 | 错误 %d 条" % (len(imp_v4), len(reg_v4), len(errs)))
    for s in imp_v4[:10]:
        print("  + " + s)
    for s in reg_v4[:10]:
        print("  - " + s)
    if errs:
        print("ERR sample:", errs[:3])


if __name__ == "__main__":
    main()