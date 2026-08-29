# -*- coding: utf-8 -*-
"""RAGFlow retrieval API 客户端（评测用）。

密钥一律从环境变量读取，禁止硬编码（仓库是公开的）：
  RAGFLOW_BASE_URL    RAGFlow 服务地址，默认 http://127.0.0.1:9380
  RAGFLOW_API_KEY     必填
  RAGFLOW_DATASET_ID  必填
"""
import json
import os
import re
import urllib.error
import urllib.request


def require_env(name):
    value = os.getenv(name, "").strip()
    if not value:
        raise SystemExit(
            f"缺少环境变量 {name}。\n"
            f"运行前请设置：$env:{name}='<value>'（PowerShell）或 export {name}=<value>。\n"
            "密钥一律走环境变量，禁止硬编码进脚本。"
        )
    return value


def ragflow_client():
    base = os.getenv("RAGFLOW_BASE_URL", "http://127.0.0.1:9380").rstrip("/")
    return RAGFlowClient(base, require_env("RAGFLOW_API_KEY"), require_env("RAGFLOW_DATASET_ID"))


class RAGFlowClient:
    def __init__(self, base, api_key, dataset_id):
        self.base = base
        self.api_key = api_key
        self.dataset_id = dataset_id

    def retrieve_ids(self, question, page_size=20, similarity_threshold=0.2,
                     vector_similarity_weight=0.3, rerank_id=""):
        body = {
            "question": question,
            "dataset_ids": [self.dataset_id],
            "page_size": page_size,
            "similarity_threshold": similarity_threshold,
            "vector_similarity_weight": vector_similarity_weight,
            "rerank_id": rerank_id,
        }
        req = urllib.request.Request(
            self.base + "/api/v1/retrieval",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + self.api_key},
        )
        try:
            with urllib.request.urlopen(req, timeout=25) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"RAGFlow HTTP {e.code}: {e.read()[:300]}")
        except urllib.error.URLError as e:
            raise RuntimeError(
                f"RAGFlow 不可达（{e.reason}），请确认 Docker Desktop 已启动且 9380 端口可达"
            )
        chunks = (payload.get("data") or {}).get("chunks") or []
        return [self.normalize(c.get("document_keyword")) for c in chunks]

    @staticmethod
    def normalize(raw):
        m = re.search(r"(\d+)", raw or "")
        if not m:
            return ""
        return "%03d" % int(m.group(1))