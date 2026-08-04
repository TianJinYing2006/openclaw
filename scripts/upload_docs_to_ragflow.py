#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 data/fashion_docs/outfit_*.md 批量上传到 RAGFlow 知识库并触发解析。

用法:
  python scripts/upload_docs_to_ragflow.py --dataset-id <ID> --api-key <KEY>
  （或设置环境变量 RAGFLOW_BASE_URL / RAGFLOW_API_KEY / RAGFLOW_DATASET_ID）

流程:
  1. 分批上传 markdown 文档 (POST /api/v1/datasets/{id}/documents)
  2. 触发解析 (POST /api/v1/datasets/{id}/chunks)
  3. 轮询每个文档的解析进度直到完成
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOCS_DIR = PROJECT_ROOT / "data/fashion_docs"
BATCH_SIZE = 10  # 单批上传文档数
MAX_POLL_SECONDS = 600  # 单个文档解析最长等待


def parse_args():
    parser = argparse.ArgumentParser(description="Upload fashion markdown docs to RAGFlow")
    parser.add_argument("--base-url", default=os.environ.get("RAGFLOW_BASE_URL", "http://127.0.0.1:9380"))
    parser.add_argument("--api-key", default=os.environ.get("RAGFLOW_API_KEY", ""))
    parser.add_argument("--dataset-id", default=os.environ.get("RAGFLOW_DATASET_ID", ""))
    return parser.parse_args()


def headers(api_key):
    return {"Authorization": f"Bearer {api_key}"}


def upload_batch(base, api_key, dataset_id, files):
    """上传一批 md 文件，返回 doc id 列表。"""
    url = f"{base}/api/v1/datasets/{dataset_id}/documents"
    payload = []
    for f in files:
        payload.append(("file", (f.name, f.read_bytes(), "text/markdown")))
    resp = requests.post(url, headers=headers(api_key), files=payload, timeout=120)
    if resp.status_code != 200:
        raise RuntimeError(f"upload failed {resp.status_code}: {resp.text[:500]}")
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"upload error: {data}")
    docs = data.get("data") or []
    ids = [d["id"] for d in docs]
    print(f"  uploaded {len(ids)} docs: {[d['name'] for d in docs]}")
    return ids


def trigger_parse(base, api_key, dataset_id, doc_ids):
    url = f"{base}/api/v1/datasets/{dataset_id}/chunks"
    resp = requests.post(url, headers=headers(api_key),
                         json={"document_ids": doc_ids}, timeout=60)
    if resp.status_code != 200:
        raise RuntimeError(f"parse trigger failed {resp.status_code}: {resp.text[:500]}")
    data = resp.json()
    if data.get("code") != 0:
        raise RuntimeError(f"parse trigger error: {data}")


def doc_status(base, api_key, dataset_id, doc_id):
    """通过文档列表接口查询单个文档的解析状态。"""
    url = f"{base}/api/v1/datasets/{dataset_id}/documents"
    resp = requests.get(url, headers=headers(api_key),
                        params={"page": 1, "page_size": 300}, timeout=30)
    if resp.status_code != 200:
        return {"run": "FAIL", "progress": 0}
    data = resp.json().get("data") or {}
    docs = data.get("docs") or data.get("documents") or []
    for doc in docs:
        if doc.get("id") == doc_id:
            return {"run": doc.get("run", "UNSTART"), "progress": doc.get("progress", 0)}
    return {"run": "UNSTART", "progress": 0}


def main():
    args = parse_args()
    if not args.api_key or not args.dataset_id:
        print("缺少 --api-key / --dataset-id（或对应环境变量）", file=sys.stderr)
        sys.exit(1)
    if not DOCS_DIR.exists():
        print(f"文档目录不存在: {DOCS_DIR}", file=sys.stderr)
        sys.exit(1)

    docs = sorted(DOCS_DIR.glob("outfit_*.md"))
    print(f"共 {len(docs)} 篇文档 -> {args.base_url} dataset={args.dataset_id}")

    all_ids = []
    for i in range(0, len(docs), BATCH_SIZE):
        batch = docs[i:i + BATCH_SIZE]
        print(f"batch {i // BATCH_SIZE + 1}:")
        all_ids.extend(upload_batch(args.base_url, args.api_key, args.dataset_id, batch))

    print(f"触发解析 {len(all_ids)} 篇...")
    trigger_parse(args.base_url, args.api_key, args.dataset_id, all_ids)

    # 轮询全部完成
    deadline = time.time() + MAX_POLL_SECONDS
    pending = set(all_ids)
    while pending and time.time() < deadline:
        done = []
        for doc_id in pending:
            status = doc_status(args.base_url, args.api_key, args.dataset_id, doc_id)
            if status["run"] in ("DONE", "DONE_WITH_ERROR"):
                done.append(doc_id)
            elif status["run"] == "FAIL":
                print(f"  doc {doc_id} parse FAILED")
                done.append(doc_id)
        if done:
            pending.difference_update(done)
            print(f"  完成 {len(all_ids) - len(pending)}/{len(all_ids)}")
        if pending:
            time.sleep(10)
    if pending:
        print(f"警告: {len(pending)} 篇文档超时未完成", file=sys.stderr)
        sys.exit(2)
    print("全部文档解析完成")


if __name__ == "__main__":
    main()
