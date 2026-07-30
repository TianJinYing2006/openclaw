# 服装数据主链与公共参考库

## 1. 当前目标

当前版本先把服装数据的“保存、标准化、索引、检索”主链打通，不批量导入尚未整理完成的采集目录，
也不在这一阶段实现推荐打分、Rerank 或训练模型。第一波范围是男装的 `TOP`、`BOTTOM`、`OUTERWEAR`；鞋子先保留在原始标注 JSON，暂不进入单品库和向量库。

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

Agent 工具结果内部可以携带业务 ID 供下一步 Tool 调用，但微信回复不得显示数据库 ID、UUID、英文枚举或向量分数。

## 4. 本地启动

1. 启动 MySQL 3306。
2. 当 `app.persistence.redis.enabled=true` 时启动 Redis 6379。
3. 启动 Qdrant：

```powershell
docker compose -f infra/qdrant/compose.yml up -d
```

4. 启动 `YkdSummerApplication`。本地配置启用语义检索时，应用通过 Qdrant gRPC 6334 连接容器。

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

当前已对真实标注和 `wet_001` 至 `wet_012` 的 24 张既有切图完成只读映射验证：每套 Look 生成 1 件上衣和 1 件下装；`SHOES` 仍保留在原始 JSON 中。真实 MySQL、OSS、Qdrant 集成导入还未启动。
`D:\小红书\wet` 的批量导入仍保持关闭，等待图片采集、授权和标注校验完成。

用于 12 套样本的真实导入配置必须显式提供切图状态来源；文件实际存在时优先于 JSONL 中陈旧的 `PENDING` 状态：

```properties
app.fashion.reference.included-categories=TOP,BOTTOM,OUTERWEAR
app.fashion.reference.cutout-job-file=D:/小红书/wet/cleaned/jobs.jsonl
app.fashion.reference.cutout-manifest-file=D:/小红书/wet/cleaned/manifest.jsonl
app.fashion.reference.cutout-image-directory=D:/小红书/wet/cleaned/images
```

每一个已完成单品切图会记录独立的 OSS 资产、文件哈希、来源文件名、生成模型、耗时和状态。再次导入相同 Look 时，哈希不变的切图会复用已有 OSS 资产；切图文件暂时消失也不会把已确认的 `READY` 资产降级为 `PENDING`。

## 6. 数据治理边界

- 标注契约见 `docs/features/FASHION_IMAGE_ANNOTATION_STANDARD.md`。
- 个人衣橱允许用户修改名称和主观标签；公共素材的客观标注只能由平台流程更新。
- 导入图片在正式环境发布前必须逐张核验来源和使用权；本地样本状态不能自动代表商业授权。
- 后续推荐排序应从 MySQL 候选开始，叠加用户反馈、天气、场景和搭配规则；RAG/向量召回不能替代业务过滤。
