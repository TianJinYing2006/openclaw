# 用户画像补全设计（feature/user-profile）

> 本文档记录"规范化偏好画像断链"补全的实现方法与设计原理。

## 背景与问题

项目里存在两套用户画像：

1. **对话历史画像（已完整）**：`fashion_conversations` 三段式写入 + 向量检索 + Agent prompt 注入。
2. **规范化偏好画像（断链）**：`FashionUserPreference`（维度/值/极性/权重/置信度）——
   - 存储层 `JdbcFashionCoreRepository.upsertPreference` 已实现；
   - 消费层 `OutfitRecommendationEngine.preferenceMatch` 已接入排序加权（支持 COLOR/STYLE/FIT/PATTERN/MATERIAL/CATEGORY 六维）；
   - **唯独没有写入方**：`updatePreference()` 全项目无业务调用，导致运行中 preferences 恒为空，排序引擎的偏好加权不生效。

## 实现方法

### 1. 新增 DTO：`InferredPreference`
`ai/fashion/model/InferredPreference`：`(dimension, value, polarity)`，构造时自动大写规范化，提供 `valid()` 校验。

### 2. 新增 Prompt：`AgentPrompts.PREFERENCE_INFERENCE`
few-shot LLM 抽取器：
- dimension 约束为排序引擎支持的六维枚举；
- value 给出已知代码示例（颜色/风格/版型/类目），无法映射时退化为简洁英文短语；
- 极性 POSITIVE/NEGATIVE，与 `upsertPreference` 的 `oneOf` 校验对齐；
- 含糊评价输出空数组，避免噪声。

### 3. 新增服务：`PreferenceInferenceService`
- 依赖 `AgentLlmCaller`（构造器）+ `FashionCoreService`（`@Autowired(required=false)` setter，持久化禁用时可缺省）。
- 核心方法 `inferAndRecord(userId, feedbackText, sentiment)`：
  - LLM 抽取 → 逐条 `coreService.updatePreference`（weight=1.0，confidence=0.8，source=`FEEDBACK_INFERENCE`，evidence=反馈原文截断 200 字）；
  - 所有异常吞掉，不影响穿搭主流程。

### 4. 接线：主路径 `FashionFeedbackRecorder`（异步）

反馈检测挂在主对话路径（`AiChatService.answerInternalInContext` 开头），而非 `fashion_consultant` 工具内部：

1. **触发点**：每轮普通消息在回复主链路前调用 `FashionFeedbackRecorder.maybeRecord(userId, memoryPrompt)`；
2. **关键词预过滤（同步 O(1)）**：`FEEDBACK_KEYWORDS`（喜欢/难看/太黑/换成…）contains 命中才继续，普通聊天零开销；
3. **LLM 分类 + 写库（异步虚拟线程）**：命中后提交到 `fashionAgentParallelExecutor` 虚拟线程池执行
   `QueryAnalyzer.detectFeedback` → 回填 `fashion_conversations.user_feedback` →
   `PreferenceInferenceService.inferAndRecord`（偏好 LLM 抽取 + 写 `fashion_user_preferences`）。
   `@Autowired(required=false)` setter 注入 executor 与 preferenceInference，测试/无 Spring 场景降级同步或跳过。

> 设计取舍：反馈采集本质是"锦上添花"的数据沉淀，绝不能阻塞用户回复。分类与偏好推断合计 2 次 LLM 调用（约 1~3s），
> 若同步执行会直接加到每条命中消息的响应延迟上，故整体异步化。

## 为什么这么设计

| 决策 | 原因 |
|------|------|
| 从**用户反馈**推断而非推荐/对话 | 反馈是用户对结果的明确表态，信号最强；推荐本身不含偏好态度 |
| 用 **LLM few-shot 抽取**而非规则字典 | 中文表达多样（"太正式了""宽松一点""不喜欢西装外套"），规则字典覆盖不全；项目已有 `QueryAnalyzer.detectFeedback` 同款模式，成本可控 |
| dimension 约束与**排序引擎六维一致** | 保证 `preferenceValues(item, dimension)` 能匹配到 WardrobeItem 属性 token，偏好加权真正生效 |
| **失败静默 + 短超时（10s）** | 偏好是增强项，不能拖垮反馈路径；LLM 失败、空偏好、持久化禁用均安全降级 |
| 复用 `upsertPreference`（ON DUPLICATE KEY UPDATE） | 同一维度+值+极性幂等更新，重复反馈覆盖 weight/confidence，不产生脏数据 |
| 独立服务 + setter 注入 | 与 `FashionAgentService` 主构造器解耦，不破坏既有测试 |

## 配置项

无新增配置（沿用 `app.ai.fashion-model` 与 `AgentLlmCaller` 的超时/模型约定）。

## 兼容性与降级

| 场景 | 行为 |
|------|------|
| 非反馈消息 | 不触发偏好推断 |
| LLM 抽取失败/超时 | 静默跳过，主流程不受影响 |
| 无明确偏好（含糊评价） | 返回空数组，不写偏好 |
| `app.persistence.enabled=false` | `FashionCoreService` 缺省，写入自动跳过 |
| 重复反馈同维度同值 | upsert 覆盖，幂等 |

## 验证方式

1. 微信发送"帮我搭一套通勤穿搭"→ 得到推荐；
2. 发送"这套太正式了，我不喜欢西装，喜欢宽松的"→ 反馈检测命中；
3. 查库：`SELECT * FROM fashion_user_preferences WHERE app_user_id=<user>` 应出现
   `STYLE/BUSINESS NEGATIVE`、`CATEGORY/JACKET NEGATIVE`、`FIT/RELAXED POSITIVE` 等记录；
4. 再次发起衣橱推荐，`OutfitRecommendationEngine.preferenceMatch` 开始对上述偏好加权生效。

---

## 性能修复记录：推荐穿搭变慢（2026-08-05）

### 问题现象

修复画像断链（把反馈采集挂到主路径）之后，推荐回复明显变慢：实测一次推荐请求
Pipeline 阶段 18505ms（此前 9918ms）、主模型阶段 79976ms（此前 37599ms），且每轮
带"好看/喜欢/太黑"等词的对话都有肉眼可见的卡顿。

### 根因（代码问题在哪）

1. **同步 LLM 分类阻塞主链路**：`FashionFeedbackRecorder.maybeRecord` 挂在
   `AiChatService.answerInternalInContext` 开头后，关键词预过滤命中就**同步**执行
   `QueryAnalyzer.detectFeedback`（1 次 LLM，约 1s+）。而传入的 `memoryPrompt` 是
   拼接的历史上下文，只要历史里出现过"喜欢/好看/太黑"等词，每一轮都会命中关键词，
   导致每次回复前都白等一次 LLM 分类。
2. **consult 内重复检测**：`FashionAgentService.consult` 中旧的
   `recordFeedbackIfAny` 对同一用户输入又跑一遍 `detectFeedback` + 偏好推断
   （`PreferenceInferenceService.inferAndRecord` 内部还是 1 次 LLM 抽取）。
   推荐请求自带"好看/搭一套"等词，等于推荐管道前后串行跑了 2 次反馈 LLM。
3. **偏好推断是第二重同步 LLM**：`inferAndRecord` 的偏好抽取也是 LLM 调用，
   与分类 LLM 串行累加，同步路径总耗时约 2~3s/轮。

### 设计方案（怎么修的）

| 问题 | 方案 |
|------|------|
| 同步 LLM 阻塞主链路 | **预过滤保持同步（O(1)），分类+写库异步化**：`maybeRecord` 关键词命中后
  `pool.execute(() -> classifyAndRecord(...))` 立即返回，提交到
  `fashionAgentParallelExecutor`（虚拟线程池，按需创建线程，不占平台线程）；
  executor 未装配（单测）时降级同步执行 |
| consult 内重复检测 | **删除** `FashionAgentService.recordFeedbackIfAny` 方法及调用，
  反馈检测统一收敛到主路径 `FashionFeedbackRecorder`（唯一入口，不再重复） |
| 偏好推断串行 LLM | 随整体异步化进入虚拟线程池，不再出现在主链路耗时中 |

### 改动文件

- `FashionFeedbackRecorder.java`：新增 `executor` 字段（`@Qualifier("fashionAgentParallelExecutor")`，
  `@Autowired(required=false)` 注入）；`maybeRecord` 改为"同步预过滤 + 异步执行"，
  原同步逻辑移入私有 `classifyAndRecord`。
- `FashionAgentService.java`：移除 `recordFeedbackIfAny` 及 `feedbackRecorder` 字段/注入；
  `conversationService`/`queryAnalyzer` 字段保留以兼容既有构造签名与测试。
- `AiChatService.java`：调用点不变（`recorder.maybeRecord(...)`），行为由同步变异步。

### 预期效果与验证

- 主链路对反馈采集的耗时降为 O(1)（一次 contains 扫描），推荐/普通回复延迟恢复原水平；
- 反馈与偏好画像仍正常沉淀（异步线程池写入，观察 `fashion_user_preferences` 持续有新增）；
- 对比方式：同一条推荐请求，观察 `runtime-bot.out.log` 中 Pipeline / 主模型阶段耗时回落。
