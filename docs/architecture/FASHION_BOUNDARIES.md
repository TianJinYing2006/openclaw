# 两套 Fashion 子系统边界契约（FASHION_BOUNDARIES）

> 对应需求三：**合并或明确割裂两套 fashion 系统（规划）**。
> 结论：**采用「明确割裂 + 契约」方案（Plan B），不做硬合并（Plan A）。**
> 本文件是两套系统的边界契约，作为后续改动的审查基线。

---

## 1. 背景与定位

项目里长期并存两套定位完全不同的 fashion 能力，曾经因为包名相近（`ai.fashion` / `fashion`）而被误读为「同一系统的两部分」。字节级 DTO 比对确认二者**输出模型零字段重叠**，是互补而非重复实现：

| 维度 | A = Look 引擎 | B = 衣橱引擎 |
| --- | --- | --- |
| 包（重命名后） | `com.example.ykdsummer.ai.fashion.look` | `com.example.ykdsummer.fashion.wardrobe` |
| 用户问题 | “博主/灵感里这套怎么穿” | “怎么搭配**我自己**的衣服” |
| 技术形态 | 多 Agent LLM 流水线（Stylist/Critic/Trend/Coordinator） | DDD 领域 + 确定性 8 维评分引擎 |
| 关键引用 | `outfit_编号` / RAG 文档编号 | `wardrobeItemId` |
| 输出 | 自然语言搭配方案（`StylistOutput`/`CoordinatorOutput`/`FashionResult`） | 结构化 DB 引用 + 评分（`OutfitRecommendationResult`：`wardrobeItemId`/`role`/`categoryCode`/`colorPrimary`/`totalScore`/`evidence`） |

> 比对结论：A 的输出是 `top/bottom/shoes/accessories/styleLabel` 等自然语言串；B 的输出是 `wardrobeItemId/role/categoryCode/colorPrimary/totalScore/evidence` 等结构化字段。二者**零字段重叠**，硬合并（Plan A）会摧毁语义边界，故废弃。

---

## 2. 三条边界规则

### 规则一：归属（Ownership）

- **A（Look 引擎）**归属 Agent / LLM 流水线，负责「公开灵感怎么穿」。
- **B（衣橱引擎）**归属领域 / 确定性引擎，负责「我的衣服怎么搭」。

### 规则二：依赖方向（Dependency Direction）—— 最核心

- ✅ **A 可以依赖 B**：Look 引擎允许调用衣橱能力（如读取用户衣橱单品图）。
- ❌ **B 严禁依赖 A**：衣橱引擎**不得 import 任何 `ai.fashion.look` 的类**。
- 🟰 **`common.fashion` 为中性共用层**：A、B 都可依赖它；它**不依赖 A 也不依赖 B**。

> 已修复的违规实例：衣橱引擎的 `FashionTryOnTools` 曾直接 `import ai.fashion.look.FashionAgentService` 并调用 `buildGarmentCollage`，构成 B→A 反向依赖。MVP-B 已把相关的 `GarmentImage` 类型与 `buildGarmentCollage` 拼图方法下沉到 `common.fashion`，使 B 改为依赖共用层（见 §3）。

### 规则三：数据表归属（Table Ownership）

- 对话 / 灵感类表（如 `fashion_conversations`）→ **A（Look 引擎）**。
- 其余 `fashion_*`（衣橱、单品、试衣、评分、参考图索引等）→ **B（衣橱引擎）**。
- 跨表访问须经对方 `Service` / `Repository`，**不得直接跨包触碰对方持久化层**。

---

## 3. 共用层 `common.fashion`

- 位置：`com.example.ykdsummer.common.fashion`
- 承载内容：
  - `ReferenceImageResolver`（`@Component`）：读取 `data/image_urls.json`，按 `outfit` 编号返回整套原图 + 分割单品图 URL；含数据类型 `GarmentImage`、`OutfitImages`。
  - `GarmentCollage`：单品图（上衣 + 下装）上下拼接工具，静态方法 `buildGarmentCollage(byte[], byte[])`。
- 约束：**不得 import `ai.fashion.look` 或 `fashion.wardrobe` 的任何类**；保持零业务逻辑依赖，仅做中性数据 / 工具载体，供两套引擎共享。

---

## 4. 回归防护（如何发现违规）

依赖方向红线（CI / 人工审查均可）：

```bash
# 若命中，说明衣橱引擎又依赖了 Look 引擎，违反规则二
grep -rn "import com.example.ykdsummer.ai.fashion.look" \
  src/main/java/com/example/ykdsummer/fashion/wardrobe
```

可选增强（见 Full-B 规划）：引入 **ArchUnit** 测试在 CI 强制规则二，避免再次滑回 B→A 依赖。

---

## 5. 历史决策记录

- **2026-08**：需求三评审。确认 Plan A（硬合并两套系统）错误——字节级 DTO 比对显示 A/B 输出模型零字段重叠，硬合并会摧毁语义边界。采纳 **Plan B（明确割裂 + 契约 + 共用层下沉）**。
- **MVP-B 交付物**：
  1. 下沉 `GarmentImage` 类型与 `buildGarmentCollage` 到 `common.fashion`，**消除 B→A 反向依赖**（已编译验证通过）。
  2. 本文档（边界契约）。
  3. 包语义化重命名：`ai.fashion` → `ai.fashion.look`，`fashion` → `fashion.wardrobe`（让边界在包名上自解释）。

> 注：Full-B（接口化 `FashionAgentWorkflowContextProvider` + ArchUnit + 过期文档清理）为可选增强，不在 MVP-B 范围内。
