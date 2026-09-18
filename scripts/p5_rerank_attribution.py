#!/usr/bin/env python3
"""P5 规则重排归因分析：可救未救（gt 在池但 rank>=5）卡在哪。

读 logs/p5_rule_rerank_pools.tsv（query/gt/场景/季节/风格/正式度/候选池），
对每条可救未救重放代表性规则权重，输出：gt 标签是否齐全、4 信号命中、
规则加分后能否进 top-5。标准库即可，无第三方依赖。
"""
import re
import pathlib
from collections import Counter

TSV = pathlib.Path("logs/p5_rule_rerank_pools.tsv")
DOCS = pathlib.Path("data/fashion_docs")

OVERVIEW = re.compile(r"## 整套搭配概览\s*\n(.*?)(?=\n## |\Z)", re.S)

SCENE_MAP = {
    "通勤": "WORKPLACE", "上班": "WORKPLACE", "工作": "WORKPLACE", "办公": "WORKPLACE", "开会": "WORKPLACE",
    "正式场合": "FORMAL_EVENT", "婚礼": "FORMAL_EVENT", "结婚": "FORMAL_EVENT", "晚宴": "FORMAL_EVENT", "宴会": "FORMAL_EVENT",
    "校园": "SCHOOL", "上学": "SCHOOL", "学生": "SCHOOL",
    "旅行": "TRAVEL", "旅游": "TRAVEL", "出差": "TRAVEL",
    "户外": "OUTDOOR", "运动": "OUTDOOR", "健身": "OUTDOOR", "跑步": "OUTDOOR", "爬山": "OUTDOOR",
    "海边": "OUTDOOR", "海滩": "OUTDOOR", "度假": "OUTDOOR",
    "日常": "DAILY", "休闲": "DAILY", "逛街": "DAILY",
}
SEASON_MAP = {"春天": "SPRING", "春季": "SPRING", "夏天": "SUMMER", "夏季": "SUMMER",
              "秋天": "AUTUMN", "秋季": "AUTUMN", "冬天": "WINTER", "冬季": "WINTER"}


def parse_pool(pool):
    out = []
    if not pool:
        return out
    for item in pool.split(","):
        if ":" in item:
            oid, s = item.rsplit(":", 1)
            try:
                out.append((oid.strip(), float(s)))
            except ValueError:
                pass
    return out


def parse_tags(text):
    def lst(name):
        m = re.search(name + r"[:：]\s*(.+?)\s*(?:\n|$)", text)
        if not m or not m.group(1).strip():
            return []
        return [x for x in re.split(r"[/、，,\s]+", m.group(1).strip()) if x]

    fm = re.search(r"整体正式度[:：]\s*([\d.]+)/5", text)
    return {
        "styles": lst("整体风格"),
        "seasons": lst("适合季节"),
        "scenes": lst("适合场合"),
        "formality": float(fm.group(1)) if fm else 0.0,
    }


def load_tags(oid):
    p = DOCS / ("outfit_%s.md" % oid)
    if not p.exists():
        return None
    m = OVERVIEW.search(p.read_text(encoding="utf-8"))
    if not m:
        return None
    return parse_tags(m.group(1).strip())


def scene_match(q, scenes):
    qs = (q or "").upper()
    return any(SCENE_MAP.get(s.strip(), s.strip().upper()) == qs for s in scenes)


def season_match(q, seasons):
    qs = (q or "").upper()
    return any(SEASON_MAP.get(s.strip(), s.strip().upper()) == qs for s in seasons)


def style_match(hint, styles):
    if not hint:
        return False
    qs = set(re.split(r"[/、，,\s]+", hint))
    return any(s in qs for s in styles)


def formality_match(qf, tf):
    return qf > 0 and tf > 0 and abs(qf - tf) <= 1.0


# 代表性权重（semantic 主导 + 规则补偏，接近网格最优区间）
W = dict(sem=0.70, scene=0.60, season=0.20, style=0.20, formality=0.15, cap=0.80)


def final_score(oid, sem, q_scene, q_season, q_style, q_form, tags_all):
    tags = tags_all.get(oid)
    rule = 0.0
    if tags:
        if scene_match(q_scene, tags["scenes"]):
            rule += W["scene"]
        if season_match(q_season, tags["seasons"]):
            rule += W["season"]
        if style_match(q_style, tags["styles"]):
            rule += W["style"]
        if formality_match(q_form, tags["formality"]):
            rule += W["formality"]
    rule = min(W["cap"], rule)
    return W["sem"] * sem + (1 - W["sem"]) * rule, rule


def main():
    lines = TSV.read_text(encoding="utf-8").splitlines()
    rows = []
    for ln in lines[1:]:
        if not ln.strip():
            continue
        parts = ln.split("\t")
        if len(parts) < 7:
            continue
        rows.append(dict(query=parts[0], gt=parts[1], scene=parts[2], season=parts[3],
                         style=parts[4], formality=int(parts[5]), pool=parse_pool(parts[6])))

    all_ids = set()
    for r in rows:
        for oid, _ in r["pool"]:
            all_ids.add(oid)
        all_ids.add(r["gt"])
    tags_all = {oid: load_tags(oid) for oid in all_ids}
    missing_tags = sum(1 for t in tags_all.values() if t is None)

    recalled = 0
    baseline_top5 = 0
    savable = []
    for r in rows:
        ids = [oid for oid, _ in r["pool"]]
        if r["gt"] not in ids:
            continue
        recalled += 1
        order = sorted(r["pool"], key=lambda x: -x[1])
        rank = next((i for i, (oid, _) in enumerate(order) if oid == r["gt"]), -1)
        if 0 <= rank < 5:
            baseline_top5 += 1
        elif rank >= 5:
            savable.append(r)

    print("== 总体 ==")
    print("query 数: %d" % len(rows))
    print("召回（gt 在池）: %d/%d = %.2f%%" % (recalled, len(rows), recalled * 100.0 / len(rows)))
    print("baseline top-5（纯语义排序）: %d/%d = %.2f%%" % (baseline_top5, len(rows), baseline_top5 * 100.0 / len(rows)))
    print("可救未救（gt 在池但 baseline rank>=5）: %d 条" % len(savable))
    print("outfit 标签覆盖: %d/%d（缺 %d）" % (sum(1 for t in tags_all.values() if t), len(tags_all), missing_tags))

    print("\n== 逐条可救未救诊断（代表权重 sem=%.2f scene=%.2f season=%.2f style=%.2f formality=%.2f cap=%.2f）=="
          % (W["sem"], W["scene"], W["season"], W["style"], W["formality"], W["cap"]))
    rescued = 0
    no_tags = 0
    still_miss = 0
    signal_hits = Counter()
    rescued_detail = []
    for r in savable:
        q = r["query"]
        qs = (q[:34] + "...") if len(q) > 34 else q
        order = sorted(r["pool"], key=lambda x: -x[1])
        gt_sem = next(s for oid, s in r["pool"] if oid == r["gt"])
        fifth_sem = order[4][1]
        gt_tags = tags_all.get(r["gt"])
        if gt_tags is None:
            no_tags += 1
            print("X %s | gt=%s baseline_rank=%d | 标签缺失，规则死区" % (qs, r["gt"], r["base_rank"] + 1 if "base_rank" in r else r["pool"].__len__()))
            continue
        hm_scene = scene_match(r["scene"], gt_tags["scenes"])
        hm_season = season_match(r["season"], gt_tags["seasons"])
        hm_style = style_match(r["style"], gt_tags["styles"])
        hm_form = formality_match(r["formality"], gt_tags["formality"])
        for k, h in (("scene", hm_scene), ("season", hm_season), ("style", hm_style), ("formality", hm_form)):
            if h:
                signal_hits[k] += 1
        scored = []
        for oid, sem in r["pool"]:
            f, _rule = final_score(oid, sem, r["scene"], r["season"], r["style"], r["formality"], tags_all)
            scored.append((oid, f))
        scored.sort(key=lambda x: -x[1])
        new_rank = next((i for i, (oid, _) in enumerate(scored) if oid == r["gt"]), -1)
        ok = 0 <= new_rank < 5
        if ok:
            rescued += 1
            rescued_detail.append(r["gt"])
        else:
            still_miss += 1
        gap = fifth_sem - gt_sem
        print("%s %s | gt=%s rank %d->%d | 语义缺口+%.4f | 命中 scene=%d season=%d style=%d formality=%d"
              % ("V 救回" if ok else "X 仍miss", qs, r["gt"],
                 next((i for i, (oid, _) in enumerate(order) if oid == r["gt"])) + 1, new_rank + 1,
                 gap, int(hm_scene), int(hm_season), int(hm_style), int(hm_form)))

    print("\n== 结论 ==")
    print("可救未救 %d：规则重放救回 %d，仍 miss %d，标签缺失死区 %d" % (len(savable), rescued, still_miss, no_tags))
    print("信号命中分布: %s" % dict(signal_hits))
    if signal_hits:
        print("信号命中占比（按可救未救样本 %d 条计）: %s"
              % (len(savable), {k: "%.0f%%" % (v * 100.0 / len(savable)) for k, v in signal_hits.items()}))


if __name__ == "__main__":
    main()