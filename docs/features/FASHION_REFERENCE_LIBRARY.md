# 服装数据主链与公共参考库

## 1. 当前目标

当前版本先把服装数据的“保存、标准化、索引、检索”主链打通，并在已验证的小样本上实现基于搭配证据的
确定性衣橱推荐，不批量导入尚未整理完成的采集目录，也不训练推荐模型。第一波范围是男装的
`TOP`、`BOTTOM`、`OUTERWEAR`；鞋子先保留在原始标注 JSON，暂不进入单品库和向量库。

```text
公共整套 Look 图片 + 标准化 Look JSON
  -> OSS 保存整套图和已完成的单品切图
  -> MySQL 保存 Look、Garment、完整标注和切图状态
  -> MySQL Outbox 记录待索引任务
  -> 百炼 text-embedding-v4 生成 1024 维文本向量
  -> Qdrant 保存可重建的向量与最小业务定位信息
  -> Agent 按私有/公共范围调用检索工具
```

MySQL 是业务事实源，OSS 是图片事实源，Qdrant 只是可重建的检索索引。删除 Qdrant 集合不会删除衣橱或图片，
但需要重新投递索引任务后才能恢复语义检索。

## 2. 私有衣橱与公共参考的边界

| 范围 | MySQL 模型 | Qdrant scope | 修改权限 | 默认查询 |
| --- | --- | --- | --- | --- |
| 用户衣橱 | `fashion_wardrobe_items` | `USER_WARDROBE` | 当前绑定用户 | 是 |
| 公共参考 | `fashion_reference_looks` + `fashion_reference_garments` | `PUBLIC_REFERENCE` | 平台导入流程 | 否 |

普通的“我有哪些衣服”“找我的灰色短袖”只查当前用户衣橱，并强制携带 `appUserId`。
只有用户明确说“公共素材、参考款、搭配案例、找灵感”时，Agent 才调用 `search_fashion_references`。
公共参考不是商品，当前没有价格、库存或购买链接。

## 3. 标签和查询

图片确认入衣橱时会保留自然中文名称、父/子类目、主次颜色、风格、版型、图案、季节、场景、材质、
置信度以及完整识别 JSON。结构化筛选采用 AND 语义，非空条件必须同时满足。

- `search_wardrobe`：明确类目、颜色、风格等结构化条件。
- `search_wardrobe_semantic`：场景化、模糊描述或搭配语义，Qdrant 不可用时降级到 MySQL。
- `show_wardrobe_items`：按相同条件返回个人衣橱图片。
- `search_fashion_references`：只查询公共参考 Look，可同时叠加结构化条件。
- `recommend_outfits_from_wardrobe`：选定一件个人衣服后，聚合完成召回、证据反查、衣橱匹配和排序。

Agent 工具结果内部可以携带业务 ID 供下一步 Tool 调用，但微信回复不得显示数据库 ID、UUID、英文枚举或向量分数。

## 4. 本地启动

1. 启动 MySQL 3306。
2. 当 `app.persistence.redis.enabled=true` 时启动 Redis 6379。
3. 启动 Qdrant：

```powershell
docker compose -f infra/qdrant/compose.yml up -d
```

4. 启动 `YkdSummerApplication`。本地配置启用语义检索时，应用通过 Qdrant gRPC 6334 连接容器。

推荐聚合 Tool 只有在 MySQL 持久化、服装语义检索和公共参考库均启用时注册。开发环境至少需要：

```properties
app.persistence.enabled=true
app.fashion.semantic.enabled=true
app.fashion.reference.enabled=true
app.fashion.outfit-recommendation.enabled=true
```

`app.fashion.reference.import-enabled` 仍应保持 `false`；启用查询和推荐不等于再次执行数据导入。

Docker 只负责运行 Qdrant 进程并把 6333/6334 映射到本机；Java 仍像连接 MySQL 一样通过端口连接它。
Qdrant 数据使用 Docker volume 持久化，容器停止或 Java 重启不会自动丢失。

## 5. 少量公共样本导入

导入默认关闭。需要导入时临时配置：

```properties
app.fashion.reference.enabled=true
app.fashion.reference.import-enabled=true
app.fashion.reference.annotation-file=D:/创意/穿搭图片标准化标注_1.0.0_前3张.json
app.fashion.reference.image-directory=D:/创意
app.fashion.reference.import-limit=3
app.fashion.reference.publish-imported=true
```

启动一次并看到导入完成日志后，把 `import-enabled` 改回 `false`。导入按 `reference_code` 幂等更新，并按
SHA-256 阻止相同图片换文件名后二次入库。只有 `ACTIVE` 素材会进入公共检索。

当前已对真实标注和 `wet_001` 至 `wet_012` 的 24 张既有切图完成真实集成导入：12 个 Look、12 件上衣、12 件下装已写入 MySQL/OSS，并在 Qdrant 写入 12 个 `REFERENCE_LOOK` 和 24 个 `REFERENCE_GARMENT` 文本向量；`SHOES` 仍保留在原始 JSON 中。
`D:\小红书\wet` 的 230 套批量导入仍保持关闭，等待图片采集、授权和标注校验完成。

用于 12 套样本的真实导入配置必须显式提供切图状态来源；文件实际存在时优先于 JSONL 中陈旧的 `PENDING` 状态：

```properties
app.fashion.reference.included-categories=TOP,BOTTOM,OUTERWEAR
app.fashion.reference.cutout-job-file=D:/小红书/wet/cleaned/jobs.jsonl
app.fashion.reference.cutout-manifest-file=D:/小红书/wet/cleaned/manifest.jsonl
app.fashion.reference.cutout-image-directory=D:/小红书/wet/cleaned/images
```

每一个已完成单品切图会记录独立的 OSS 资产、文件哈希、来源文件名、生成模型、耗时和状态。再次导入相同 Look 时，哈希不变的切图会复用已有 OSS 资产；切图文件暂时消失也不会把已确认的 `READY` 资产降级为 `PENDING`。

## 6. 基于搭配证据的推荐链

```text
用户选定个人衣橱单品
  -> Qdrant 只召回同类公共 Garment 业务定位
  -> MySQL 批量反查 Garment 所属公共 Look
  -> 聚合同套 TOP/BOTTOM/OUTERWEAR 关系
  -> 只在当前用户衣橱中匹配真实候选
  -> 规则评分、组合去重和多样性排序
  -> 持久化 1 至 3 套推荐快照
  -> 后台生成无人物搭配图，失败则回退真实抠图拼接板
  -> iLink 主动回传微信
```

推荐快照使用三张表：

- `fashion_outfit_recommendation_runs`：一次请求及场景、天气和缺失单品结论。
- `fashion_outfit_recommendation_options`：每套方案的排名、分项评分、证据摘要和异步渲染状态。
- `fashion_outfit_recommendation_items`：方案中的真实用户衣橱单品，并固定当时使用的图片版本。

评分由服务端确定性执行，默认考虑公共 Look 证据、场景、季节天气、颜色、风格、版型正式度、用户偏好和
近期重复。属性缺失时该维度不扣分，剩余有效权重重新归一化；LLM 只负责理解请求和解释结果，不重新打分。
如果没有公共证据，不推断用户缺什么；有证据但个人衣橱无法匹配时，才输出具体缺失单品。

推荐和效果图解耦。每套效果图独立经历 `SUBMITTED -> PROCESSING -> SUCCEEDED/FALLBACK/FAILED`；
应用重启会把中断的 `PROCESSING` 恢复为 `SUBMITTED`。图片供应商失败不删除推荐，系统改用用户真实抠图生成搭配板。
默认适配器由 `app.fashion.outfit-recommendation.render-provider=image-edit` 选择；核心服务只依赖
`OutfitRenderService` 接口，后续替换 VTON 或其他效果图供应商不需要修改推荐算法。
最近一次推荐保存在 MySQL，短期聊天记忆清空或应用重启后，Agent 仍能可靠理解“第一套、第二套”。

## 7. 数据治理边界

- 标注契约见 `docs/features/FASHION_IMAGE_ANNOTATION_STANDARD.md`。
- 个人衣橱允许用户修改名称和主观标签；公共素材的客观标注只能由平台流程更新。
- 导入图片在正式环境发布前必须逐张核验来源和使用权；本地样本状态不能自动代表商业授权。
- 推荐排序从 MySQL 用户候选开始，Qdrant 只召回公共搭配证据；RAG/向量召回不能替代用户隔离、业务过滤和确定性评分。
- 当前不上 RAGFlow、不导入全部 230 套、不接商品价格库存、不训练模型、不自动虚拟试衣，也不实现反馈学习。
