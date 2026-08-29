#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
上传穿搭参考图片到阿里云 OSS（对象级公读），并生成 data/image_urls.json 映射表。

数据源:
  src/main/resources/分割单品/{outfit_id}/*.png   -> fashion-reference/outfits/{outfit_id}/xxx.png
  src/main/resources/整套原图/{id}_*.webp|png     -> fashion-reference/outfits/{outfit_id}/overview.{ext}

OSS 凭据读取优先级:
  1. 环境变量 OSS_ENDPOINT / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET / OSS_BUCKET_NAME
  2. src/main/resources/application-local.properties 中的 oss.image.*（已 gitignore，不会提交）
"""
import os
import re
import sys
import json
from pathlib import Path

import oss2

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SEGMENTED_DIR = PROJECT_ROOT / "src/main/resources/分割单品"
OVERVIEW_DIR = PROJECT_ROOT / "src/main/resources/整套原图"
OUTPUT_JSON = PROJECT_ROOT / "data/image_urls.json"
PREFIX = "fashion-reference/outfits"


def load_local_properties():
    path = PROJECT_ROOT / "src/main/resources/application-local.properties"
    props = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def main():
    props = load_local_properties()
    endpoint = os.environ.get("OSS_ENDPOINT") or props.get("oss.image.endpoint")
    ak_id = os.environ.get("OSS_ACCESS_KEY_ID") or props.get("oss.image.access-key-id")
    ak_secret = os.environ.get("OSS_ACCESS_KEY_SECRET") or props.get("oss.image.access-key-secret")
    bucket_name = os.environ.get("OSS_BUCKET_NAME") or props.get("oss.image.bucket-name")
    if not all([endpoint, ak_id, ak_secret, bucket_name]):
        print("缺少 OSS 配置（endpoint/access-key-id/access-key-secret/bucket-name），"
              "请检查 application-local.properties 或环境变量", file=sys.stderr)
        sys.exit(1)

    auth = oss2.Auth(ak_id, ak_secret)
    bucket = oss2.Bucket(auth, endpoint, bucket_name)

    mapping = {}

    # 1) 分割单品
    for outfit_dir in sorted(SEGMENTED_DIR.iterdir()):
        if not outfit_dir.is_dir():
            continue
        outfit_id = outfit_dir.name
        files = sorted(outfit_dir.glob("*.png"))
        if not files:
            continue
        entries = []
        for f in files:
            key = f"{PREFIX}/{outfit_id}/{f.name}"
            m = re.match(r"\d+_\d+_(.+)\.png", f.name)
            garment = m.group(1) if m else "item"
            bucket.put_object_from_file(key, str(f), headers={"x-oss-object-acl": "public-read"})
            url = f"https://{bucket_name}.{endpoint}/{key}"
            entries.append({"garment": garment, "file": f.name, "url": url})
            print(f"  uploaded {outfit_id}/{f.name} -> {key}")
        mapping[outfit_id] = {"garments": entries, "overview": None}

    # 2) 整套原图
    for f in sorted(OVERVIEW_DIR.iterdir()):
        if not f.is_file():
            continue
        m = re.match(r"(\d+)_", f.name)
        if not m:
            continue
        outfit_id = m.group(1)
        key = f"{PREFIX}/{outfit_id}/overview{f.suffix.lower()}"
        bucket.put_object_from_file(key, str(f), headers={"x-oss-object-acl": "public-read"})
        url = f"https://{bucket_name}.{endpoint}/{key}"
        entry = mapping.setdefault(outfit_id, {"garments": [], "overview": None})
        entry["overview"] = url
        print(f"  uploaded {f.name} -> {key}")

    # 3) 写 image_urls.json
    OUTPUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_JSON.write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(len(v["garments"]) for v in mapping.values())
    overviews = sum(1 for v in mapping.values() if v.get("overview"))
    print(f"\n完成: {len(mapping)} 套, {total} 张分割单品图, {overviews} 张整套原图")
    print(f"映射表已写入: {OUTPUT_JSON}")


if __name__ == "__main__":
    main()
