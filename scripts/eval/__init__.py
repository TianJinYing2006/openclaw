# -*- coding: utf-8 -*-
"""RAG 检索评测统一入口包。

子模块：
- ragflow.py    RAGFlow retrieval API 客户端（密钥走环境变量）
- query_build.py 检索词构造（V1/V2/V3，与线上 QueryAnalyzer 口径对齐）
- metrics.py    命中率指标（top-1/top-5/top-20）
- report.py     Markdown 报告生成
- run.py        CLI 入口
"""