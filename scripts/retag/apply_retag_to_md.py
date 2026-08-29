#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 retag 提案（英文）翻译成中文标签，精确替换 data/fashion_docs/outfit_*.md
「整套搭配概览」里【当前塌缩】的维度。已良好的标签原样保留，避免回退。

为什么翻译 + 只换塌缩维度：
- Look 引擎重排（RuleRerankGridSearchLiveTest / RagFlowKnowledgeService）解析的是
  md 的中文标签：场景/季节走中文归一化（通勤→WORKPLACE、春季→SPRING…），
  风格是中文精确 token 匹配（查询风格词来自 {优雅,休闲,街头,浪漫,甜美,商务,
  通勤,复古,学院,极简,性感}）。直接用英文 patch 会让风格/场景重排失效。
- 因此风格必须对齐到上述查询词表；季节/场合用 retag 模块的 SEASON_CN/OCCASION_CN。
- 只替换“当前塌缩”的维度：season∈{夏季,四季} 且含夏季 / occasion⊆{日常,休闲} /
  style 单元数≤1 或⊆{休闲,日常}。已非塌缩的维度（如某套 春季/秋季·户外/旅行/通勤）
  保持原样，防止把丰富标签改“瘦”。

用法：
  python apply_retag_to_md.py            # 干跑，打印改动清单 + 样例
  python apply_retag_to_md.py --apply    # 先备份 data/fashion_docs，再写
"""
import csv
import json
import re
import sys
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
TSV = ROOT / "logs" / "retag" / "outfit_retag_proposals.tsv"
DOCS = ROOT / "data" / "fashion_docs"
BACKUP = ROOT / "logs" / "retag" / "fashion_docs_backup_before_retag"

sys.path.insert(0, str(HERE))
import retag_outfits_fashion_florence as R  # 复用 SEASON_CN / OCCASION_CN

# 英文风格 → 中文（对齐查询风格词表）
STYLE_CN = {
    "casual": "休闲", "classic": "通勤", "chic": "优雅", "trendy": "街头",
    "sporty": "休闲", "sport": "休闲", "athletic": "休闲", "activewear": "休闲",
    "street": "街头", "streetwear": "街头",
    "minimalist": "极简", "minimal": "极简",
    "edgy": "街头",
    "elegant": "优雅", "romantic": "浪漫", "preppy": "学院", "vintage": "复古",
    "retro": "复古", "bohemian": "复古",
    "workwear": "通勤", "formal": "商务", "glamorous": "优雅", "party": "优雅",
    "sexy": "性感", "sweet": "甜美", "beach": "休闲",
}

LABEL_RE = {
    "style": re.compile(r"^- 整体风格[:：]\s*(.*?)\s*$"),
    "season": re.compile(r"^- 适合季节[:：]\s*(.*?)\s*$"),
    "occasion": re.compile(r"^- 适合场合[:：]\s*(.*?)\s*$"),
    "formality": re.compile(r"^- 整体正式度[:：]\s*(.*?)\s*$"),
}


def cn_list(en_csv, cmap):
    out = []
    for tok in (en_csv or "").split("/"):
        tok = tok.strip().lower()
        if not tok:
            continue
        cn = cmap.get(tok, tok)
        if cn not in out:
            out.append(cn)
    return out


def parse_current(lines):
    cur = {}
    for ln in lines:
        for dim, rx in LABEL_RE.items():
            m = rx.match(ln)
            if m:
                cur[dim] = m.group(1).strip()
    return cur


def is_collapsed(dim, val):
    if not val:
        return False
    toks = [t for t in re.split(r"[/、，,\s]+", val) if t]
    s = set(toks)
    if dim == "season":
        # 含“夏季”或“四季”即视为塌缩（夏季主导 / 无区分度）。
        # 像 002 的「春季/秋季」不含夏/四季 → 非塌缩，原样保留。
        return bool(s) and ("夏季" in s or "四季" in s)
    if dim == "occasion":
        # 含默认塌缩词（日常/休闲）且未充分分化（≤2 词）即视为塌缩，
        # 纯「通勤」「约会」等真实分化词则保留。
        return bool(s) and (("日常" in s or "休闲" in s) and len(s) <= 2)
    if dim == "style":
        # 含默认塌缩词（休闲/日常）且未充分分化（≤2 词）即视为塌缩，
        # 如「休闲/简约」「休闲/通勤」应被提案的丰富风格词替换。
        return bool(s) and (("休闲" in s or "日常" in s) and len(s) <= 2)
    if dim == "formality":
        # 正式度本身不视为“塌缩”，仅当其他维度塌缩时一并跟进
        return False
    return False


def main():
    apply = "--apply" in sys.argv
    rows = list(csv.DictReader(open(TSV, encoding="utf-8"), delimiter="\t"))
    plan = []
    changed_files = 0
    skipped_good = 0

    for r in rows:
        oid = r["oid"]
        md = DOCS / f"outfit_{oid}.md"
        if not md.exists():
            plan.append({"oid": oid, "status": "md_missing"})
            continue
        lines = md.read_text(encoding="utf-8").splitlines()
        cur = parse_current(lines)

        # 翻译提案
        style_cn = cn_list(r["proposed_style"], STYLE_CN)
        season_cn = cn_list(r["proposed_season"], R.SEASON_CN)
        occ_cn = cn_list(r["proposed_occasion"], R.OCCASION_CN)
        try:
            fv = int(str(r["proposed_formality"]).split("/")[0])
        except Exception:
            fv = 3
        form_cn = f"{fv}.0/5"

        # 判定哪些维度塌缩（需替换）
        todo = {}
        if is_collapsed("season", cur.get("season", "")):
            todo["season"] = "适合季节：" + "/".join(season_cn) if season_cn else "适合季节：四季"
        if is_collapsed("occasion", cur.get("occasion", "")):
            todo["occasion"] = "适合场合：" + "/".join(occ_cn) if occ_cn else "适合场合：日常"
        if is_collapsed("style", cur.get("style", "")):
            todo["style"] = "整体风格：" + "/".join(style_cn) if style_cn else "整体风格：休闲"
        # 正式度：仅当本文件有其他维度要改时才一并更新，保持内部一致
        if todo:
            todo["formality"] = "整体正式度：" + form_cn

        if not todo:
            skipped_good += 1
            plan.append({"oid": oid, "status": "good_keep",
                         "current": {k: cur.get(k, "") for k in ("style", "season", "occasion", "formality")}})
            continue

        # 生成新文件内容（只换 todo 维度）
        new_lines = []
        for ln in lines:
            replaced = False
            for dim, rx in LABEL_RE.items():
                if dim in todo and rx.match(ln):
                    new_lines.append(todo[dim])
                    replaced = True
                    break
            if not replaced:
                new_lines.append(ln)
        changed_files += 1
        plan.append({
            "oid": oid, "status": "change", "changed": list(todo.keys()),
            "before": {k: cur.get(k, "") for k in ("style", "season", "occasion", "formality")},
            "after": {k: todo[k] for k in todo},
        })

        if apply:
            md.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    # 输出
    print(f"提案套数: {len(rows)}")
    print(f"将修改 md 文件数: {changed_files}")
    print(f"已良好保留(不改)的套数: {skipped_good}")
    missing = [p for p in plan if p["status"] == "md_missing"]
    if missing:
        print(f"TSV 中有但 md 缺失: {len(missing)} -> {[p['oid'] for p in missing][:10]}")

    changes = [p for p in plan if p["status"] == "change"]
    print("\n===== 前 8 个改动样例 =====")
    for p in changes[:8]:
        print(f"\noid={p['oid']}  替换维度: {','.join(p['changed'])}")
        for dim in ("style", "season", "occasion", "formality"):
            if dim in p["after"]:
                print(f"  - {dim}:  [{p['before'].get(dim,'')}]  ->  [{p['after'][dim]}]")

    (ROOT / "logs" / "retag" / "apply_plan.json").write_text(
        json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n完整改动清单已写: logs/retag/apply_plan.json")

    if apply:
        print(f"\n[APPLY] 已写入 {changed_files} 个 md 文件。")


if __name__ == "__main__":
    if "--apply" in sys.argv:
        if not BACKUP.exists():
            shutil.copytree(DOCS, BACKUP)
            print(f"[BACKUP] 已备份 data/fashion_docs -> {BACKUP}")
        else:
            print(f"[BACKUP] 已存在，跳过: {BACKUP}")
    main()
