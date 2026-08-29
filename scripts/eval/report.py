# -*- coding: utf-8 -*-
"""RAG 检索评测 Markdown 报告生成。

注意：报告文件只写索引号（#idx）与 GT 编号，不透出真实用户查询文本；
原始文本仅允许打印到本地控制台。
"""
from datetime import datetime


def render(meta, result):
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    total = result["rows"]
    lines = ["# RAG 检索评测报告", ""]
    lines.append(f"- 时间: {now}")
    lines.append(f"- RAGFlow: {meta['base']}")
    lines.append(f"- 评测数据: {meta['data_path']}")
    lines.append(f"- 样本量: {total} 条（真实用户查询，GT=系统实际采纳参考穿搭编号，自动标注）")
    lines.append("")
    lines.append("| 变体 | top-1 | top-5 | top-20 | 检索异常 |")
    lines.append("| --- | --- | --- | --- | --- |")
    for name in result["names"]:
        h = result["hits"][name]
        lines.append(
            f"| {name} | {h['top1']}/{total} = {_pct(h['top1'], total)} | "
            f"{h['top5']}/{total} = {_pct(h['top5'], total)} | "
            f"{h['top20']}/{total} = {_pct(h['top20'], total)} | {h['errors']} |"
        )
    base = result["names"][0]
    lines.append("")
    lines.append(f"## 对照（以「{base}」为基准，top-5 翻转）")
    for name in result["names"]:
        if name == base:
            continue
        mv = result["moves"][name]
        lines.append(f"### {name}")
        lines.append(f"- 救回 {len(mv['improved'])} 条 / 退步 {len(mv['regressed'])} 条")
        for idx, gt in mv["improved"][:10]:
            lines.append(f"  + #{idx} (gt={gt})")
        for idx, gt in mv["regressed"][:10]:
            lines.append(f"  - #{idx} (gt={gt})")
    if result["errors"]:
        lines.append("")
        lines.append("## 检索异常")
        for e in result["errors"][:10]:
            lines.append("- " + e.split(" | ")[0] + " | " + " | ".join(e.split(" | ")[1:]))
    lines.append("")
    lines.append("> 说明：评测依赖 RAGFlow（9380 端口）与 Docker Desktop，未启动时命中率会假性为 0%。")
    return "\n".join(lines)


def _pct(n, total):
    return "0.0%" if total == 0 else ("%.1f%%" % (100.0 * n / total))