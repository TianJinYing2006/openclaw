# 项目文档导航

按下面顺序阅读即可，不需要一次看完所有资料。

| 你想做什么 | 先看哪里 |
| --- | --- |
| 第一次运行项目 | [入门运行指南](getting-started/ILINK_GUIDE.md) |
| 看懂代码从哪里启动、消息怎样流转 | [代码导读](getting-started/ILINK_CODE_WALKTHROUGH.md) → [完整时序图](architecture/ILINK_PROJECT_SEQUENCE_GUIDE.md) |
| 了解每个包和扩展点 | [项目结构说明](architecture/PROJECT_STRUCTURE.md) |
| 理解穿搭 Agent 多 Agent 编排 | [Agent 编排设计](AGENT_ORCHESTRATION.md) → [穿搭 Agent 设计](fashion-agent-design.md) |
| 了解穿搭业务逻辑与场景 | [穿搭业务逻辑](fashion-business-logic.md) → [演示用例](features/FASHION_AGENT_DEMO_CASES.md) |
| 理解穿搭 RAG 检索设计 | [衣橱 RAG 设计](wardrobe-rag-design.md) → [参考库说明](features/FASHION_REFERENCE_LIBRARY.md) |
| 引用 RAG 命中率数字前 | [RAG 指标口径统一](rag-metrics-canonical.md) |
| 判断 Critic/多 Agent 是否值得 | [Critic 消融实验](critic-ablation.md) → [消融实测](critic-ablation-results.md) → [判官实测](agent-judge-results.md) |
| 查看 Agent 工具范围与白名单 | [工具范围说明](features/FASHION_AGENT_TOOL_SCOPE.md) |
| 了解图片标注规范 | [标注标准](features/FASHION_IMAGE_ANNOTATION_STANDARD.md) |
| 开发视频理解功能 | [视频分析指南](features/ILINK_VIDEO_ANALYSIS_GUIDE.md) |
| 复刻或迁移 iLink AI 机器人 | [AI 复刻任务书](features/ILINK_AI_REPLICATION_GUIDE.md) |
| 搭建本地管理站 | [管理站搭建](admin-site-setup.md) |
| 配置本地持久化（MySQL/Redis） | [本地持久化配置](local-persistence-setup.md) |
| MCP 替换方案（网页搜索/抠图/衣橱分析/天气/试衣） | [01-网页搜索](mcp-replacement-01-web-search.md) → [02-抠图](mcp-replacement-02-garment-cutout.md) → [03-衣橱分析](mcp-replacement-03-wardrobe-analysis.md) → [04-天气](mcp-replacement-04-weather.md) → [05-虚拟试衣](mcp-replacement-05-virtual-tryon.md) |
| 查看统筹方案与进度 | [穿搭统筹方案](fashion-master-plan.md) |
| 查看 Agent 能力补强落地度 | [需求落地度记录](requirements-completion-status.md) |
| 发布 / 演示前自检 | [Release Checklist](release-checklist.md) |
| 学 Git、分支和合并 | [Git 协作指南](development/GIT_GUIDE.md) |
| 查看历史方案或分支整合决策 | [历史资料](history/) |

目录说明：

- `getting-started/`：如何启动和读代码。
- `architecture/`：项目结构、模块边界和时序。
- `features/`：某一项具体能力的实现说明。
- `development/`：开发协作工具说明。
- `history/`：保留决策记录和项目交接文档，不是日常开发的第一阅读入口。
