#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
retag_outfits_fashion_florence.py
==================================

用开源视觉模型 **Fashion Florence v2**（`anushreeberlia/fashion-florence-v2`，
Florence-2-large 0.77B + LoRA 适配器）对项目中 161 套小红书 outfit 图片做
**结构化属性重打标**，产出可人工抽检的提案，用于修复 `data/fashion_docs/outfit_*.md`
里塌缩的「适合场合 / 适合季节 / 整体风格 / 整体正式度」标签。

为什么是它（见 docs/data_quality_audit.md「业界方案调研」）：
- 适配器已公开发布，**零训练直接推理**；
- 输出 8 字段 JSON，含 occasion_tags / season_tags / style_tags；
- 在单品级评测中 category F1≈0.90、style F1 0.888，显著优于 GPT-4o-mini / Gemini 零样本。

注意（务必人工抽检后再落库）：
1. Fashion Florence 在**单品（产品图）**上训练，而本项目 `overview.webp` 是整套搭配拼图。
   本脚本同时跑「每个 garment png」+「overview.webp」，以单品级为主、overview 为辅做聚合，
   提升 occasion / season 推断稳定性（v2 的 occasion/season 本身也是规则推导的）。
2. 输出的 **occasion / season / formality 是透明启发式规则推导**（见下方 *_RULE 字典），
   不是模型直接预测。提案里带 reasoning + source，便于审计。
3. 本脚本**只产出提案，绝不直接覆盖** `data/fashion_docs/*.md`。落库需人工 review 后执行。

依赖（在「有外网 + 有 GPU」的环境安装）：
    pip install torch torchvision transformers peft pillow requests
（CUDA 版 torch 可自行选；RTX 4060 8GB 跑 fp16 0.77B 模型充足）

运行：
    # 真实推理（需网络下载模型 + GPU）
    python retag_outfits_fashion_florence.py \
        --image-urls ../../data/image_urls.json \
        --out-dir ../../logs/retag

    # 只跑前 5 套试水
    python retag_outfits_fashion_florence.py --limit 5

    # 断点续跑（已写入 tsv 的 oid 自动跳过）
    python retag_outfits_fashion_florence.py --resume

    # 离线逻辑自测（无需 torch / 网络，仅标准库）
    python retag_outfits_fashion_florence.py --self-test

环境变量：
    HF_ENDPOINT   可指向 HF 镜像（如 https://hf-mirror.com），用于模型下载加速/绕过封锁。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BASE_MODEL = "anushreeberlia/fashion-florence"          # Florence-2-large 0.77B（被适配器微调的基座）
ADAPTER = "anushreeberlia/fashion-florence-v2"           # 8 字段 LoRA 适配器
PROMPT = "Analyze this clothing item image and return structured fashion tags as JSON."

# 默认路径（相对本脚本位于 scripts/retag/）
_HERE = Path(__file__).resolve().parent
_PROJECT_ROOT = _HERE.parent.parent
DEFAULT_IMAGE_URLS = _PROJECT_ROOT / "data" / "image_urls.json"
DEFAULT_OUT_DIR = _PROJECT_ROOT / "logs" / "retag"

# ---------------------------------------------------------------------------
# 透明启发式规则（推导 occasion / season / formality）
# 仅用于「模型不直接输出」的字段；每条都可被审计/修改。
# ---------------------------------------------------------------------------
# 风格词 -> 场合（取并集，对齐官方 fashion-florence 规则表）
OCCASION_RULE: dict[str, list[str]] = {
    "sporty":     ["gym", "workout"],
    "athletic":   ["gym", "workout"],
    "activewear": ["gym", "workout"],
    "sport":      ["gym", "workout"],
    "sexy":       ["party", "night-out"],
    "glamorous":  ["party", "night-out"],
    "edgy":       ["going-out", "night-out"],
    "elegant":    ["dinner", "date", "formal-event"],
    "classic":    ["work", "everyday"],
    "workwear":   ["work"],
    "preppy":     ["work", "everyday"],
    "bohemian":   ["vacation", "casual"],
    "streetwear": ["everyday", "casual"],
    "minimalist": ["everyday", "work"],
    "casual":     ["everyday", "casual"],
    "trendy":     ["everyday", "going-out"],
    "chic":       ["everyday", "date"],
    "vintage":    ["everyday", "casual"],
    "statement":  ["party", "going-out"],
    "romantic":   ["date", "everyday"],
}
# 类目 -> 场合（补充；缺省 everyday/casual，见 aggregate）
OCCASION_BY_CATEGORY: dict[str, list[str]] = {
    "shoes":     ["everyday"],
    "accessory": ["everyday"],
    "layer":     ["everyday", "casual"],
}
# 材质 -> 季节（取并集，对齐官方 fashion-florence 规则表；fall=秋季）
SEASON_RULE: dict[str, list[str]] = {
    "wool":      ["autumn", "winter"],
    "cashmere":  ["autumn", "winter"],
    "fleece":    ["autumn", "winter"],
    "tweed":     ["autumn", "winter"],
    "velvet":    ["autumn", "winter"],
    "velour":    ["autumn", "winter"],
    "flannel":   ["autumn", "winter"],
    "corduroy":  ["autumn", "winter"],
    "knit":      ["autumn", "winter"],
    "linen":     ["spring", "summer"],
    "chiffon":   ["spring", "summer"],
    "organza":   ["spring", "summer"],
    "tulle":     ["spring", "summer"],
    "lace":      ["spring", "summer", "autumn"],
    "satin":     ["spring", "summer", "autumn"],
    "silk":      ["spring", "summer", "autumn"],
    "cotton":    ["spring", "summer", "autumn"],
    "denim":     ["spring", "autumn", "winter"],
}
SEASON_BY_CATEGORY: dict[str, list[str]] = {
    "coat": ["autumn", "winter"],
    "sweater": ["autumn", "winter"],
    "jacket": ["autumn", "winter", "spring"],
    "shorts": ["summer"],
    "swimwear": ["summer"],
    "sandals": ["summer"],
    "boots": ["autumn", "winter"],
}
# 风格词 -> 正式度分值（取最大值，缺省 3）
FORMALITY_RULE: dict[str, int] = {
    "formal": 5, "classic": 4, "workwear": 4, "elegant": 5, "romantic": 4,
    "preppy": 4, "minimalist": 3, "casual": 2, "street": 2, "sport": 1,
    "athletic": 1, "beach": 1, "bohemian": 2, "vintage": 3, "retro": 3,
    "party": 4, "glamorous": 4, "sexy": 4, "sweet": 3,
}
DEFAULT_FORMALITY = 3

# 中英文映射（输出落库时用中文，便于直接替换 markdown 标签）
OCCASION_CN = {
    "work": "通勤", "formal-event": "正式场合", "date": "约会", "party": "派对",
    "night-out": "夜场", "gym": "健身", "workout": "运动", "everyday": "日常",
    "casual": "休闲", "vacation": "度假", "going-out": "外出", "dinner": "晚宴",
    "sport": "运动", "beach": "海边", "travel": "旅行", "festival": "音乐节",
    "daily": "日常", "school": "校园", "retro": "复古",
}
SEASON_CN = {
    "spring": "春季", "summer": "夏季", "fall": "秋季", "autumn": "秋季",
    "winter": "冬季", "all_season": "四季",
}


# ---------------------------------------------------------------------------
# 纯逻辑：聚合 + 推导（不依赖任何外部库，self-test 用）
# ---------------------------------------------------------------------------
def _norm(tag: str) -> str:
    return (tag or "").strip().lower()


def _garment_seasons(f: dict) -> list[str]:
    """单品级「材质/类目 -> 季节」集合（去重保序），供 outfit 级多数投票使用。"""
    f = f or {}
    seasons: list[str] = []
    mat = _norm(f.get("material", ""))
    seasons += SEASON_RULE.get(mat, [])
    cat = _norm(f.get("category", ""))
    seasons += SEASON_BY_CATEGORY.get(cat, [])
    seen: set[str] = set()
    out: list[str] = []
    for s in seasons:
        if s not in seen:
            seen.add(s)
            out.append(s)
    return out


def aggregate_outfit(per_item_fields: list[dict], overview_fields: dict | None = None):
    """把若干单品级预测聚合为 outfit 级标签。

    per_item_fields: 每个 garment 的预测（6 字段 JSON，见 Fashion Florence v2 schema）
    overview_fields: overview.webp 的预测（可选，权重更低）
    返回 outfit 级 {style, season, occasion, formality, reasoning, source}
    """
    all_fields = list(per_item_fields) + ([overview_fields] if overview_fields else [])

    style_tags: list[str] = []
    for f in all_fields:
        for t in (f or {}).get("style_tags", []) or []:
            t = _norm(t)
            if t and t not in style_tags:
                style_tags.append(t)

    # occasion 推导（对齐官方：style 映射优先；仅当所有 style 均无贡献才回退 category default，避免 everyday/casual 噪声）
    occasion: list[str] = []
    style_hit = False
    for f in all_fields:
        f = f or {}
        for t in style_tags:
            for occ in OCCASION_RULE.get(t, []):
                if occ not in occasion:
                    occasion.append(occ)
                    style_hit = True
    if not style_hit:
        for f in all_fields:
            f = f or {}
            cat = _norm(f.get("category", ""))
            for occ in OCCASION_BY_CATEGORY.get(cat, ["everyday", "casual"]):
                if occ not in occasion:
                    occasion.append(occ)

    # season 推导：单品级「材质/类目 -> 季节」取并集后，按 **多数投票** 聚合
    # （而非简单并集）。并集会让混搭套轻易覆盖四季（如 cotton 春/夏/秋 +
    # denim 春/秋/冬 => 四季全含），多数投票只保留「出现在一半以上单品」的季节，
    # 显著提升季节区分度。overview 仅作辅助、不计入投票。
    season_counts: dict[str, int] = {}
    n_g = len(per_item_fields)
    for f in per_item_fields:
        for s in _garment_seasons(f):
            season_counts[s] = season_counts.get(s, 0) + 1
    season = [s for s, c in season_counts.items() if n_g > 0 and c / n_g > 0.5]
    if not season and season_counts:  # 极端情况：无季节过半，回退到最高票
        mx = max(season_counts.values())
        season = [s for s, c in season_counts.items() if c == mx]
    if not season:
        season = ["all_season"]  # 材质/类目均未命中时的兜底

    # formality 推导：有风格标签时取标签分值的最大值，无标签才回退默认
    scores = [FORMALITY_RULE.get(t) for t in style_tags]
    scores = [s for s in scores if s is not None]
    formality = max(scores) if scores else DEFAULT_FORMALITY

    reasoning = (
        f"style={style_tags}; "
        f"occasion<-style/category 规则; season<-material/category 多数投票; "
        f"formality<-style 规则(max)"
    )
    source = "fashion-florence-v2(" + str(len(per_item_fields)) + " garments"
    source += "+overview)" if overview_fields else ")"

    return {
        "style": style_tags,
        "season": season,
        "occasion": occasion,
        "formality": formality,
        "reasoning": reasoning,
        "source": source,
    }


def to_md_lines(oid: str, agg: dict) -> str:
    """生成可替换 markdown 整套搭配概览 标签行的文本。"""
    style = "/".join(agg["style"]) or "日常"
    occasion = "/".join(OCCASION_CN.get(o, o) for o in agg["occasion"]) or "日常"
    season = "/".join(SEASON_CN.get(s, s) for s in agg["season"]) or "夏季"
    formality = f"{agg['formality']}/5"
    return (
        f"# outfit_{oid}\n"
        f"## 整套搭配概览\n"
        f"- 整体风格：{style}\n"
        f"- 适合季节：{season}\n"
        f"- 适合场合：{occasion}\n"
        f"- 整体正式度：{formality}\n"
    )


# ---------------------------------------------------------------------------
# 真实推理相关（懒加载，避免无 GPU / 无网时 import 失败）
# ---------------------------------------------------------------------------
def fetch_image(url: str, timeout: int = 20, retries: int = 3):
    """下载 URL 为 PIL.Image(RGB)。self-test 时由调用方注入 mock。"""
    import io
    import requests
    from PIL import Image
    last = None
    for _ in range(retries):
        try:
            r = requests.get(url, timeout=timeout)
            r.raise_for_status()
            return Image.open(io.BytesIO(r.content)).convert("RGB")
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.0)
    raise RuntimeError(f"fetch_image failed for {url}: {last}")


def load_model(device: str | None = None):
    """加载 Florence-2 基座 + fashion-florence-v2 适配器。返回 (model, processor)。"""
    import torch
    from transformers import AutoModelForCausalLM, AutoProcessor
    from peft import PeftModel

    if device is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device.startswith("cuda") else torch.float32

    print(f"[model] loading base {BASE_MODEL} on {device} ({dtype}) ...", flush=True)
    processor = AutoProcessor.from_pretrained(BASE_MODEL, trust_remote_code=True)
    base = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL, torch_dtype=dtype, trust_remote_code=True
    )
    model = PeftModel.from_pretrained(base, ADAPTER).eval().to(device)
    print("[model] ready.", flush=True)
    return model, processor


def predict_tags(image, model, processor, device: str | None = None) -> dict:
    """对单张图推理，返回解析后的 6 字段 dict。"""
    import torch
    if device is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device.startswith("cuda") else torch.float32
    inputs = processor(text=PROMPT, images=image, return_tensors="pt").to(device, dtype)
    with torch.no_grad():
        out = model.generate(
            input_ids=inputs["input_ids"],
            pixel_values=inputs["pixel_values"],
            max_new_tokens=512,
            num_beams=1,
            do_sample=False,
        )
    raw = processor.tokenizer.decode(out[0], skip_special_tokens=True)
    return _parse_model_json(raw)


def _parse_model_json(raw: str) -> dict:
    """从模型输出里抠出 JSON。

    容错分三层（模型偶尔会在最后一个字段的值处截断，例如输出
    `..."season_tags"]}` 这种缺值的残缺 JSON，导致直接用 json.loads 整段失败、
    进而让 aggregate 全部回退默认值而重新塌缩）：
      1) 整段 json.loads；
      2) 修复「末尾被截断的键」后重试（如 `,"season_tags"]}` -> `}`）；
      3) 逐字段正则抽取，保底恢复已经完整写出的字段（style 排最前、occasion
         次之，season 在最后，即使最后字段被截也不影响前面的字段）。
    """
    import json as _json
    import re
    start = raw.find("{")
    end = raw.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return {}
    blob = raw[start : end + 1]
    try:
        return _json.loads(blob)
    except Exception:  # noqa: BLE001
        pass
    # 修复 1：末尾被截断的键（","xxx"]}" -> "}"）
    repaired = re.sub(r',\s*"[^"]*"\s*\]\s*\}\s*$', "}", blob)
    if repaired != blob:
        try:
            return _json.loads(repaired)
        except Exception:  # noqa: BLE001
            pass
    # 修复 2：逐字段正则抽取（保底）
    result: dict = {}
    for key in ("category", "primary_color", "material", "fit"):
        m = re.search(r'"%s"\s*:\s*"([^"]*)"' % key, blob)
        if m:
            result[key] = m.group(1)
    for key in ("secondary_colors", "style_tags", "occasion_tags", "season_tags"):
        m = re.search(r'"%s"\s*:\s*(\[[^\]]*\])' % key, blob)
        if m:
            try:
                result[key] = _json.loads(m.group(1))
            except Exception:  # noqa: BLE001
                pass
    return result


# ---------------------------------------------------------------------------
# I/O
# ---------------------------------------------------------------------------
def load_image_urls(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_done_oids(tsv_path: Path) -> set[str]:
    if not tsv_path.exists():
        return set()
    done = set()
    with tsv_path.open(encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row.get("oid"):
                done.add(row["oid"])
    return done


def write_proposal(tsv_path: Path, oid: str, agg: dict) -> None:
    header = ["oid", "proposed_style", "proposed_season", "proposed_occasion",
              "proposed_formality", "reasoning", "source"]
    write_header = (not tsv_path.exists()) or (tsv_path.stat().st_size == 0)
    with tsv_path.open("a", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, delimiter="\t")
        if write_header:
            w.writeheader()
        w.writerow({
            "oid": oid,
            "proposed_style": "/".join(agg["style"]),
            "proposed_season": "/".join(agg["season"]),
            "proposed_occasion": "/".join(agg["occasion"]),
            "proposed_formality": f"{agg['formality']}/5",
            "reasoning": agg["reasoning"],
            "source": agg["source"],
        })


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def run(args) -> int:
    image_urls = load_image_urls(args.image_urls)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    tsv_path = out_dir / "outfit_retag_proposals.tsv"
    patch_path = out_dir / "outfit_retag_patch.md"
    cache_path = out_dir / "per_item_cache.json"
    cache: dict = {}
    if args.resume and cache_path.exists():
        try:
            cache = json.loads(cache_path.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            cache = {}
    elif cache_path.exists():
        # 非续跑：清空旧缓存，全量重算（沙箱禁止 unlink，用截断写代替）
        cache_path.write_text("{}", encoding="utf-8")

    done = load_done_oids(tsv_path) if args.resume else set()
    if done:
        print(f"[resume] {len(done)} oids already done, skipping.", flush=True)

    # 非续跑：清空旧提案，确保全量重算（沙箱禁止 unlink，用截断写代替）
    if not args.resume and tsv_path.exists():
        with tsv_path.open("w", encoding="utf-8", newline="") as _f:
            pass

    model, processor = load_model(args.device) if not args.self_test else (None, None)

    oids = sorted(image_urls.keys())
    if args.limit:
        oids = oids[: args.limit]

    patch_blocks: list[str] = []
    processed = 0
    for oid in oids:
        if oid in done:
            continue
        entry = image_urls[oid]
        per_item: list[dict] = []
        # 单品级（主信号）
        for g in entry.get("garments", []):
            url = g.get("url")
            if not url:
                continue
            img = fetch_image(url)
            per_item.append(predict_tags(img, model, processor, args.device))
        # overview 级（辅助）
        overview_fields = None
        ov_url = entry.get("overview")
        if ov_url:
            overview_fields = predict_tags(fetch_image(ov_url), model, processor, args.device)

        agg = aggregate_outfit(per_item, overview_fields)
        cache[oid] = {"per_item": per_item, "overview": overview_fields}
        cache_path.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
        write_proposal(tsv_path, oid, agg)
        patch_blocks.append(to_md_lines(oid, agg))
        processed += 1
        print(f"[ok] {oid}: style={agg['style']} season={agg['season']} "
              f"occasion={agg['occasion']} formality={agg['formality']}/5", flush=True)

    patch_path.write_text("\n".join(patch_blocks), encoding="utf-8")
    print(f"\n[done] processed {processed} outfits.", flush=True)
    print(f"  proposals tsv : {tsv_path}", flush=True)
    print(f"  md patch draft: {patch_path}", flush=True)
    return 0


# ---------------------------------------------------------------------------
# 离线重聚合（不加载模型 / 不联网，秒级；依赖 run() 留下的 per_item_cache.json）
# 用途：调整 aggregate_outfit 里的季节/场合/正式度规则后，无需重跑 GPU 即可刷新提案。
# ---------------------------------------------------------------------------
def reaggregate(args) -> int:
    image_urls = load_image_urls(args.image_urls)
    _ = image_urls  # 仅用于路径校验
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cache_path = out_dir / "per_item_cache.json"
    if not cache_path.exists():
        print("[reaggregate] 未找到 per_item_cache.json，请先跑一次完整推理。", flush=True)
        return 1
    cache = json.loads(cache_path.read_text(encoding="utf-8"))
    tsv_path = out_dir / "outfit_retag_proposals.tsv"
    # 预建空文件（沙箱禁止 unlink，用截断写代替删除，避免 append 重复行）
    with tsv_path.open("w", encoding="utf-8", newline="") as _f:
        pass
    patch_path = out_dir / "outfit_retag_patch.md"
    patch_blocks: list[str] = []
    processed = 0
    for oid in sorted(cache.keys()):
        per_item = cache[oid].get("per_item", [])
        overview = cache[oid].get("overview")
        agg = aggregate_outfit(per_item, overview)
        write_proposal(tsv_path, oid, agg)
        patch_blocks.append(to_md_lines(oid, agg))
        processed += 1
    patch_path.write_text("\n".join(patch_blocks), encoding="utf-8")
    print(f"[reaggregate] 从缓存重聚合 {processed} 套（无模型/无网络）。", flush=True)
    print(f"  proposals tsv : {tsv_path}", flush=True)
    print(f"  md patch draft: {patch_path}", flush=True)
    return 0


# ---------------------------------------------------------------------------
# 离线自测（仅标准库，验证聚合 / TSV / 补丁逻辑）
# ---------------------------------------------------------------------------
def self_test() -> int:
    print("[self-test] running pure-stdlib logic validation ...", flush=True)
    # 婚礼风连衣裙 + 高跟鞋：应推导为 正式场合/约会，秋季冬季，正式度 5
    items = [
        {"category": "dress", "primary_color": "red", "material": "silk",
         "fit": "bodycon", "style_tags": ["elegant", "romantic", "glamorous"]},
        {"category": "shoes", "primary_color": "nude", "material": "satin",
         "fit": "", "style_tags": ["elegant"]},
    ]
    agg = aggregate_outfit(items)
    assert "formal-event" in agg["occasion"], agg
    assert "date" in agg["occasion"], agg
    assert agg["formality"] == 5, agg
    # 真丝红裙 + satin 鞋 -> 春/夏/秋（不含冬），验证材质/类目推导
    assert "spring" in agg["season"] and "summer" in agg["season"] and "autumn" in agg["season"], agg
    assert "winter" not in agg["season"], agg

    # 运动套装：应推导为 运动/健身，夏季，正式度 1
    sport = [{"category": "activewear", "material": "cotton",
              "style_tags": ["sport", "athletic"]}]
    agg2 = aggregate_outfit(sport)
    assert "gym" in agg2["occasion"] and "workout" in agg2["occasion"], agg2
    assert agg2["formality"] == 1, agg2
    assert "summer" in agg2["season"], agg2

    # 多数投票收紧：cotton(春/夏/秋) + denim(春/秋/冬) 应得 {春,秋}，不再四季全含
    mix = [
        {"category": "top", "material": "cotton"},
        {"category": "jeans", "material": "denim"},
    ]
    agg3 = aggregate_outfit(mix)
    assert set(agg3["season"]) == {"spring", "autumn"}, agg3
    assert "summer" not in agg3["season"] and "winter" not in agg3["season"], agg3

    # TSV + 补丁生成
    out_dir = DEFAULT_OUT_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    tsv = out_dir / "_selftest_proposals.tsv"
    # 预建空文件（沙箱禁止 unlink，用截断写代替删除，避免历史残留导致行数翻倍）
    with tsv.open("w", encoding="utf-8", newline="") as _f:
        pass
    write_proposal(tsv, "999", agg)
    write_proposal(tsv, "888", agg2)
    lines = tsv.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 3, lines  # header + 2
    md = to_md_lines("999", agg)
    assert "整体正式度：5/5" in md, md
    assert "适合场合：" in md and "正式场合" in md and "约会" in md, md
    try:
        tsv.unlink()  # 不污染产物
    except OSError:
        pass

    print("[self-test] PASS: aggregation, formality, season, occasion, tsv, md all OK.", flush=True)
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Fashion Florence v2 批量重打标（产出提案）")
    p.add_argument("--image-urls", default=str(DEFAULT_IMAGE_URLS), help="image_urls.json 路径")
    p.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR), help="提案输出目录")
    p.add_argument("--limit", type=int, default=0, help="只处理前 N 套（试水）")
    p.add_argument("--resume", action="store_true", help="跳过已写入 tsv 的 oid")
    p.add_argument("--device", default=None, help="cuda / cpu（默认自动）")
    p.add_argument("--self-test", action="store_true", help="离线逻辑自测（无需 GPU/网络）")
    p.add_argument("--reaggregate", action="store_true",
                   help="仅用 per_item_cache.json 离线重聚合（不加载模型/不联网）")
    args = p.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.reaggregate:
        return reaggregate(args)
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
