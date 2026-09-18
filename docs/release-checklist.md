# Release Checklist（交付收口）

> 用途：发布 / 面试演示前逐项自检。标注 `[本地]` 的可用本地命令验证；`[外部]` 的需外部条件。

## 1. 构建与测试

- [ ] `[本地]` JDK 21（`java -version`）
- [ ] `[本地]` Maven 编译成功：`mvn -B -DskipTests test-compile`
- [ ] `[本地]` 单元测试全绿：`mvn test`（当前 **449 passed / 0 failed**）
- [ ] `[本地]` 集成测试全绿：`mvn test -Pintegration`（当前 **48 passed / 32 skipped**，需 MySQL；Redis 用于图 checkpoint）
- [ ] `[本地]` 一键验证：`.\scripts\verify.ps1 -Mode unit|integration|all|smoke`
- [ ] `[本地]` 数据库迁移成功（Flyway 至 **V27**）
- [ ] `[本地]` Docker Compose 启动成功：`docker compose up -d --build`
- [ ] `[外部]` CI 两个 job（unit / integration）通过且上传了 surefire 报告

## 2. Agent Runtime 护栏

- [ ] `[本地]` Planner 失败时有关键词兜底（`AnalyzedQuery.fallback`）
- [ ] `[本地]` Graph 递归上限生效（`CompileConfig.recursionLimit(25)`）
- [ ] `[本地]` 单次 run deadline 生效（`app.fashion.graph.budget.run-deadline`）
- [ ] `[本地]` Critic 节点级 deadline 生效（`critic-deadline`）
- [ ] `[本地]` ToolBudget 生效（`RunBudgetTracker`：全局 + 单工具上限）
- [ ] `[本地]` ToolTimeout 生效（`GovernedToolCallback` 按 `ToolPolicy.timeoutMillis`）
- [ ] `[本地]` ToolAudit 生效（轨迹 `TYPE_TOOL` step，输入脱敏）
- [ ] `[本地]` checkpoint 可恢复（`FashionGraphRedisRecoveryIntegrationTest`）
- [ ] `[本地]` 用户确认流程可恢复（`FashionGraphHitlTest`）
- [ ] `[本地]` 重复确认不重复执行副作用（`ConfirmationServiceTest` 结果重放）
- [ ] `[本地]` 过期确认可清理（`ConfirmationServiceTest.expireOverdue`）

## 3. 安全

- [ ] `[外部]` 已泄露密钥**全部轮换**（百炼/博查/uapis/高德/RAGFlow/OSS）
- [ ] `[外部]` Git 历史、日志、压缩包无有效密钥（Gitleaks/TruffleHog）
- [ ] `[本地]` 后台鉴权开启（`/admin/**` 需 `ROLE_ADMIN`）
- [ ] `[本地]` 后台禁止默认/弱口令启动（`AdminSecurityConfiguration`）
- [ ] `[本地]` MCP Bearer 鉴权可配置（`app.mcp.auth-token` / `MCP_AUTH_TOKEN`）
- [ ] `[本地]` 敏感参数脱敏（工具审计只存 sha256+长度；日志脱敏 userId）
- [ ] `[本地]` 管理接口权限边界（端口隔离 + 本机限制）

## 4. 端到端（微信真机，`[外部]`）

- [ ] 微信真机发送普通消息得到回复
- [ ] 命中付费意图时收到「确认/取消」提示（`FASHION_GRAPH_HITL_ENABLED=true`）
- [ ] 回复「确认」后恢复并执行一次副作用
- [ ] 重复发送「确认」返回原结果，**不重复执行**
- [ ] 过期确认不再继续执行
- [ ] 图片生成失败时能恢复或降级

## 5. 文档与配置一致性

- [ ] README 测试命令/数字与 `pom.xml` profile 一致
- [ ] `docs/requirements-completion-status.md` 状态表与代码一致
- [ ] 配置项在代码、`application-*.properties`、README/`.env.example` 三处一致
- [ ] `[外部]` 打 release commit / tag

## 已知边界（面试主动说明）

1. **HITL 内存兜底非高可用**：`JdbcConfirmationStore` 在持久化关闭时用进程内内存，
   重启丢失、不跨实例共享，仅用于开发与降级，不作为生产最终一致性方案。
2. **“副作用成功但落库失败”窗口**：外部副作用已成功、`markResolved` 未落库时，重复确认仍可能重放。
   缓解方向：外部请求携带幂等键、outbox/事件、持久化外部任务 ID、失败窗口补偿扫描。
3. **确认唯一键维度**：当前为 `(run_id, action)`；若 action 依赖输入参数，建议改为
   `run_id + action + requestHash`，避免同 run 不同参数被误判为同一确认。
4. **图内 tool_loop**（默认关闭）使用独立 `ChatClient`，不经网关工具治理包装。
5. **3.2 图状态强类型化**仍为有意推迟的技术债（框架 checkpoint 序列化约束）。
