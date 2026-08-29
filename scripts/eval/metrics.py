# -*- coding: utf-8 -*-
"""检索命中率指标：top-1 / top-5 / top-20 与逐查询明细。

口径与 Java 回归测试（ResumeBenchmarkLiveTest）保持一致：
GT = 系统实际采纳的参考穿搭编号（自动标注，无人工标注偏差）。
"""


def compute(rows, retrieve, variants):
    """rows: [(ui, gt, aq)]；retrieve(question) -> [ids]；variants: {name: builder(aq, ui)}。

    返回 dict：
      names  变体名列表（按传入顺序，首个作为基准）
      hits   {name: {top1, top5, top20, errors}}
      moves  {name: {improved: [(idx, gt)], regressed: [(idx, gt)]}}（相对基准 top-5 翻转）
      errors [str]
      rows   样本量
    """
    names = list(variants)
    base_name = names[0]
    hits = {name: {"top1": 0, "top5": 0, "top20": 0, "errors": 0} for name in names}
    moves = {name: {"improved": [], "regressed": []} for name in names}
    errors = []

    for idx, (ui, gt, aq) in enumerate(rows):
        pools = {}
        ok = True
        for name, builder in variants.items():
            try:
                pools[name] = retrieve(builder(aq, ui))
            except Exception as e:
                pools[name] = []
                hits[name]["errors"] += 1
                errors.append(f"#{idx} {ui[:20]} | {name} | {e}")
                ok = False
        if not ok:
            continue
        for name in names:
            ids = pools[name]
            h = hits[name]
            h["top1"] += 1 if ids and gt == ids[0] else 0
            h["top5"] += 1 if gt in ids[:5] else 0
            h["top20"] += 1 if gt in ids else 0
        base = pools.get(base_name) or []
        for name in names:
            if name == base_name:
                continue
            ids = pools.get(name) or []
            if gt not in base[:5] and gt in ids[:5]:
                moves[name]["improved"].append((idx, gt))
            elif gt in base[:5] and gt not in ids[:5]:
                moves[name]["regressed"].append((idx, gt))

    return {
        "names": names,
        "hits": hits,
        "moves": moves,
        "errors": errors,
        "rows": len(rows),
    }