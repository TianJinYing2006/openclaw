# 仓库清理清单（待确认）

> 生成时间：2026-08-21
> 原则：本次只**列出**清单，不执行任何删除。你明确确认后我再分批处理。

---

## ✅ 已完成（无需确认）

1. **README.md** 修正文档漂移：
   - 工具数量 34 → **39**（实际注册），补全 6 个缺失工具行（`delete_wardrobe_item`、`purge_wardrobe_item`、`recommend_outfits_from_wardrobe`、`search_fashion_references`、`search_web`、`virtual_try_on_reference_outfit`）
   - RAG 文档数 174 → **161**（`data/fashion_docs/` 实际数量）
   - MCP 搜索说明 DuckDuckGo → **cn.bing.com**
   - 补全 ai/fashion 子包（memory/service/tool）与架构图 Vision 通道
2. **mcp-server/README.md** 修正漂移：
   - SSR 连接说明 → **streamable-http**（与 `server.py` stateless_http + `/mcp` endpoint 一致）
   - 搜索数据源 DuckDuckGo → cn.bing / 博查；`virtual_try_on` 数据源改为火山引擎 doubao-seedream-4.0
3. **.gitignore** 补漏：
   - 忽略 `.trae/`、`.workbuddy/`、`logs/`、`*.gz`
   - 忽略临时文件：`*.tmp`、`*.tmp.xml`、`*.bak`、`*.bak_*`、`probe-*.tmp`、`grill_*.tmp.xml`、`quaint-*.md`

---

## 📋 待你确认的清理项

### A. 根目录临时/垃圾文件（共 ~18 个，全部从未入库、可安全删除）

| 文件 | 大小 | 建议 |
|------|------|------|
| `test-out.log` / `test2.log` ~ `test8.log` | 0–38 KB | 删除（构建测试遗留） |
| `package-out.log` / `package2.log` ~ `package5.log` | 0 KB（空） | 删除 |
| `compile.log` | 0 KB | 删除 |
| `probe-write.tmp` | 6 B | 删除 |
| `grill_q7.tmp.xml` | 709 B | 删除 |
| `quaint-tan-chipmunk.md` | 3.6 KB | 删除 |

> ⚠️ 删除后不可恢复（未入 git）。但我已备份路径清单，如需提前备份可先执行。

### B. 未跟踪的个人/交付材料（**不建议删除**，看你想保留在仓库内还是移出）

| 路径 | 大小 | 当前状态 | 建议 |
|------|------|----------|------|
| `resume-tian-jinying/` | **26 MB** | 未跟踪 | 建议**移出仓库**（个人求职材料） |
| `tian-jinying-resume.json` | 12 KB | 未跟踪 | 同上 |
| `微信AI穿搭助手_项目验收汇报_优化版.pptx` | 80 KB | 未跟踪 | 验收交付物，建议保留或提交 |
| `demo/` | 100 KB | 未跟踪 | 演示页，建议提交或清理 |
| `docs/INTERVIEW_DEFENSE.md`、`docs/INTERVIEW_PREP_GUIDE.md`、`docs/JAVA_REVIEW_MAP.md`、`docs/AGENT_RESUME_TEMPLATE.md`、`docs/RESUME_TEMPLATE.md`、`docs/rag-benchmark-design.md` | 6 份 | 未跟踪 | 面试/简历材料混入 docs，建议移出或归入子目录 |

---

## 🔧 未改动项说明

- `src/main/java` 下 6 个文件（`AgentLlmCaller.java`、`MysqlFtsKnowledgeService.java`、`QueryAnalyzer.java`、`RagFlowClient.java`、`RagFlowProperties.java`）及 `application-fashion.properties` 在 git 工作区中显示 `M`（修改）——这是仓库里**既有的未提交修改**，与本次操作无关，未触碰。
- `BochaWebSearchTools` 未标 `@AgentTool`，其 `search_web` **实际不注册**（`search_web` 仅 mcp provider 提供）。这是现状，未改动。

---

## 请选择

- **A 组**：确认删除？（我会先备份到 `target/_cleanup_backup/` 再删）
- **B 组**：简历/PPT/demo 怎么处理？