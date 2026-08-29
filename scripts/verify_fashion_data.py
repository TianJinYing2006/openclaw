# -*- coding: utf-8 -*-
"""
穿搭参考数据一致性校验脚本。

数据实际存放于 RAGFlow 知识库（知识文档）+ OSS（图片），本地 data/ 仅为构建产物。
检查：
1. 结构完整性：image_urls.json 每个 outfit 必须含 overview + top/bottom 单品图；
2. URL 命名一致性：overview 必须为 /{id}/overview.webp，单品必须为 /{id}/{id}_N_{type}.png；
3. 网络可用性：HEAD 请求每个 OSS URL，统计 404/超时；
4. RAGFlow↔OSS 对应：RAGFlow 数据集中的文档编号与 OSS 图片编号是否一致。

用法: python scripts/verify_fashion_data.py [--no-network] [--no-ragflow]
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
URLS_JSON = ROOT / "data" / "image_urls.json"

RAGFLOW_BASE = os.getenv("RAGFLOW_BASE_URL", "http://127.0.0.1:9380")
RAGFLOW_KEY = os.getenv("RAGFLOW_API_KEY") or sys.exit("RAGFLOW_API_KEY 未设置（密钥走环境变量，禁止硬编码）")
RAGFLOW_DATASET = os.getenv("RAGFLOW_DATASET_ID") or sys.exit("RAGFLOW_DATASET_ID 未设置")

GARMENT_TYPES = {"top", "bottom", "shoes", "outerwear", "dress"}


def fetch_ragflow_documents() -> dict:
    """返回 RAGFlow 数据集中文档名 -> 文档 id 的映射。"""
    out = {}
    with httpx.Client(timeout=30) as client:
        page = 1
        while True:
            r = client.get(
                f"{RAGFLOW_BASE}/api/v1/datasets/{RAGFLOW_DATASET}/documents",
                params={"page": page, "page_size": 30},
                headers={"Authorization": f"Bearer {RAGFLOW_KEY}"},
            )
            r.raise_for_status()
            data = r.json()
            docs = data.get("data", {}).get("docs", [])
            for d in docs:
                out[d["name"]] = d["id"]
            if not data.get("data", {}).get("total") or len(out) >= data["data"]["total"]:
                break
            page += 1
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-network", action="store_true", help="跳过网络可用性检查")
    parser.add_argument("--no-ragflow", action="store_true", help="跳过 RAGFlow 文档检查")
    args = parser.parse_args()

    payload = json.loads(URLS_JSON.read_text(encoding="utf-8"))
    issues = []
    ids = list(payload.keys())
    print(f"image_urls.json 共 {len(ids)} 个 outfit: {ids[:10]} ...")

    # ---------- 1/2. 结构完整性 + 命名一致性 ----------
    for outfit_id, entry in payload.items():
        if not isinstance(entry, dict):
            issues.append(f"[{outfit_id}] 条目不是对象")
            continue
        overview = entry.get("overview", "")
        if not overview:
            issues.append(f"[{outfit_id}] 缺少 overview")
        elif not re.fullmatch(rf".*/outfits/{outfit_id}/overview\.(?:webp|png|jpe?g)", overview):
            issues.append(f"[{outfit_id}] overview URL 命名异常: {overview}")

        garments = entry.get("garments", [])
        if not isinstance(garments, list) or not garments:
            issues.append(f"[{outfit_id}] garments 为空")
        types_seen = set()
        for i, g in enumerate(garments):
            gtype = g.get("garment", "")
            fname = g.get("file", "")
            gurl = g.get("url", "")
            types_seen.add(gtype)
            if gtype not in GARMENT_TYPES:
                issues.append(f"[{outfit_id}] garment[{i}] 类型非法: {gtype!r}")
            if not gurl:
                issues.append(f"[{outfit_id}] garment[{i}] 缺少 url")
            else:
                # 单品命名约定: /{id}/{id}_N_{type}.png
                pat = rf".*/outfits/{outfit_id}/{outfit_id}_\d+_{gtype}\.(?:png|webp|jpe?g)"
                if not re.fullmatch(pat, gurl):
                    issues.append(f"[{outfit_id}] garment[{i}] URL 命名异常: {gurl}")
            if fname and fname not in gurl:
                issues.append(f"[{outfit_id}] garment[{i}] file 与 url 不一致: {fname} vs {gurl}")
        if "top" not in types_seen:
            issues.append(f"[{outfit_id}] 缺少 top 单品图")
        if "bottom" not in types_seen:
            issues.append(f"[{outfit_id}] 缺少 bottom 单品图")

    # ---------- 3. 网络可用性 ----------
    all_urls = []
    for outfit_id, entry in payload.items():
        if isinstance(entry, dict):
            if entry.get("overview"):
                all_urls.append(entry["overview"])
            all_urls.extend(g.get("url", "") for g in entry.get("garments", []) if g.get("url"))

    if not args.no_network:
        print(f"网络检查 {len(all_urls)} 个 URL ...")
        with httpx.Client(timeout=20, follow_redirects=True) as client:
            for u in all_urls:
                try:
                    r = client.head(u)
                    if r.status_code != 200:
                        issues.append(f"[网络] {r.status_code} {u}")
                except Exception as e:
                    issues.append(f"[网络] 异常 {u}: {type(e).__name__}: {e}")

    # ---------- 4. RAGFlow↔OSS 对应 ----------
    if not args.no_ragflow:
        try:
            docs = fetch_ragflow_documents()
            print(f"RAGFlow 数据集共 {len(docs)} 个文档")
            doc_nums = set()
            for name in docs:
                m = re.search(r"outfit_?(\d+)", name, re.IGNORECASE)
                if m:
                    doc_nums.add(m.group(1))
            json_ids = set(ids)
            if doc_nums != json_ids:
                only_doc = sorted(doc_nums - json_ids)
                only_json = sorted(json_ids - doc_nums)
                if only_doc:
                    issues.append(f"[RAGFlow↔OSS] 有文档无图片: {only_doc}")
                if only_json:
                    issues.append(f"[RAGFlow↔OSS] 有图片无文档: {only_json}")
            else:
                print(f"RAGFlow 文档编号与 OSS 图片编号完全对应 ({len(doc_nums)} 个)")
        except Exception as e:
            issues.append(f"[RAGFlow] 拉取文档列表失败: {type(e).__name__}: {e}")

    # ---------- 输出 ----------
    if issues:
        print(f"\n发现 {len(issues)} 个问题:")
        for it in issues:
            print("  -", it)
        return 1
    print("\n全部检查通过: 结构、命名、网络、RAGFlow 对应均无异常。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
