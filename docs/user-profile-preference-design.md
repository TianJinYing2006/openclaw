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

### 4. 接线：`FashionAgentService.recordFeedbackIfAny`
反馈检测命中（`detectFeedback.isFeedback()==true`）且反馈已回填 `user_feedback` 后，追加调用 `preferenceInference.inferAndRecord(...)`。通过 `@Autowired(required=false)` setter 注入，测试/无 Spring 场景可缺省。

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
