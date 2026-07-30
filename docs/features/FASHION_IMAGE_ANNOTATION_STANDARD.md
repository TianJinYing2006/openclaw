# AI 服装图片识别与标准化标注规范

> 文档版本：`1.0.1`  
> JSON 契约版本：`1.0.0`  
> 适用范围：服装图片自动识别、服装属性推测、结构化标注  
> 输出语言：字段编码使用英文稳定枚举，展示名称和说明使用中文

## 1. 文档目的

本规范只约束下面这一条链路：

```text
输入服装图片
    -> 判断图片是否可用于识别
    -> 识别图片中的全部核心服装单品
    -> 对每件单品分别推测外观和服装属性
    -> 按统一 JSON 格式输出
```

本阶段不包含：

- 用户画像；
- 穿搭推荐和搭配评分；
- 商品排序；
- 用户偏好学习；
- RAG、Embedding 和向量数据库；
- 人工修改后的业务处理；
- 图片抠图、虚拟试衣和生图。

AI 的职责是根据当前图片做标准化标注。所有无法从图片可靠确定的属性都只能标记为推测值，不得伪装成确定事实。

## 2. 核心原则

### 2.1 一图多单品，逐件标注

一张图片中可能同时出现上衣、下装、外套、连体装和鞋子。AI 必须识别所有满足条件的核心服装，并在 `garments` 数组中为每件单品输出独立对象。

例如一张人物全身照中可识别出：

```text
garment_1：白色短袖T恤
garment_2：蓝色直筒牛仔裤
garment_3：黑白低帮运动鞋
```

不得把整套穿搭合并成一件服装，也不得只输出最显眼的一件。

### 2.2 只识别当前核心类目

第一阶段只识别：

- `TOP`：上衣；
- `BOTTOM`：下装；
- `OUTERWEAR`：外套；
- `ONE_PIECE`：连体装；
- `SHOES`：鞋子。

包、帽子、腰带、围巾、首饰、眼镜等不进入 `garments`。它们可以写入 `ignoredObjects`，但不展开服装属性。

### 2.3 允许合理推测，必须表达不确定性

颜色、图案、长度等可见属性可以直接判断。

材质、弹性、透气性、保暖度等通常无法仅凭图片完全确定，可以合理推测，但必须：

1. 输出标准枚举；
2. 给出对应置信度；
3. 在 `uncertainFields` 中登记；
4. 在 `evidenceNotes` 中说明视觉依据；
5. 不得编造精确成分比例和尺寸。

### 2.4 看不清时使用 UNKNOWN

出现以下情况时必须使用 `UNKNOWN`：

- 图片分辨率不足；
- 单品被严重遮挡；
- 光线或滤镜导致颜色失真；
- 无法区分类似细分类目；
- 无法从外观推测属性；
- 枚举表中没有合理对应项。

不得为了填满字段而随机选择枚举。

### 2.5 只描述服装，不推测人物身份

不得根据人物照片推测：

- 姓名、年龄、职业；
- 身高、体重和具体三围；
- 民族、健康状况等敏感信息；
- 人物的真实消费能力；
- 人物是否拥有或购买了图片中的衣服。

`targetGender` 只表示服装设计通常面向的款式范围，不表示图片中人物的真实性别。

### 2.6 编码稳定，中文负责展示

数据库和程序使用英文枚举，例如：

```text
T_SHIRT
LIGHT_GRAY
RELAXED
SPRING
```

对用户展示时使用中文：

```text
短袖T恤
浅灰色
宽松版型
春季
```

AI 不得自行创造新的英文编码。

### 2.7 多角度图片允许先裁切正面再标注

当一张来源图片同时包含同一套穿搭的正面、背面或侧面等多个角度时，不应仅因为它是多画面排版就直接丢弃。只要其中至少有一个正面区域能够清晰辨认核心服装，就允许先执行规范化清洗：

1. 只裁取信息最完整、面积最大的正面人物区域；
2. 裁切后不得保留其他角度、拼图分隔线、网页界面、说明栏或无关边框；
3. 不得把同一来源图的不同角度分别计为多张独立穿搭；
4. 裁切后的派生图使用独立 `imageId`，并在 `warnings` 中注明“由多角度来源图裁取正面区域”；
5. 后续服装识别、置信度和不确定字段仍严格遵守本规范的 JSON 契约；
6. 本条仅补充图片清洗流程，不改变 `schemaVersion: 1.0.0` 的 JSON 字段结构。

以下情况仍应排除：

- 只有背面或侧面，无法看到足够的正面结构；
- 正面人物占比过小，裁切后分辨率不足；
- 正面被严重遮挡，无法可靠区分核心服装；
- 多个人物或多套穿搭重叠，无法确认应裁取的目标主体。

## 3. 图片级识别流程

AI 必须严格按顺序执行以下步骤。

### 步骤一：检查图片有效性

检查：

- 图片是否成功加载；
- 是否存在可识别服装；
- 主体是否过小；
- 是否严重模糊；
- 是否过曝、欠曝或强滤镜；
- 是否有大面积遮挡；
- 是否为拼图或包含多个独立画面。

如果拼图是同一套穿搭的多角度展示，且存在清晰正面，应先按 2.7 节裁取正面区域，再对清洗后的单图继续识别；不得把同一来源图的多个角度重复计数。

### 步骤二：定位核心服装

逐个查找上衣、下装、外套、连体装和鞋子。

穿在人身上的衣服同样允许识别，不要求必须是平铺商品图。只要主体轮廓、颜色和关键结构大致可见，就应输出标注。

### 步骤三：判断单品可见性

每件服装必须给出：

- `visibilityStatus`；
- `visibleRatio`；
- `occludedParts`；
- `recognitionQuality`。

建议判断标准：

| 可见比例 | 处理规则 |
| --- | --- |
| `>= 0.70` | 正常识别 |
| `0.40 - 0.69` | 允许识别，降低相关属性置信度 |
| `< 0.40` | 仅在类目和主要外观仍可判断时输出，否则忽略 |

### 步骤四：逐件提取标准属性

先判断主类目和细分类目，再判断颜色、图案、版型、长度、材质、风格、季节和其他细节。

### 步骤五：生成中文展示名称

名称必须来自真实可见特征，推荐结构：

```text
主色 + 关键图案/材质/设计 + 细分类目
```

正确示例：

- 浅灰色宽松短袖T恤；
- 黑白字母印花卫衣；
- 深蓝色直筒牛仔裤；
- 米白色长款翻领风衣；
- 黑色低帮运动鞋。

错误示例：

- `GRAY 上衣`；
- 衣服1；
- 好看的裤子；
- 时尚单品；
- 用户的衣服。

名称一般控制在 4 至 16 个中文字符，不添加编号、数据库 ID 或置信度。

### 步骤六：执行输出前校验

输出前必须确认：

- 每件单品只有一个 `categoryCode`；
- 每件单品只有一个 `subCategoryCode`；
- 主色只有一个；
- 风格、季节和场景可以多选；
- 所有编码都来自本规范；
- 置信度位于 `0.00` 至 `1.00`；
- 多件服装没有被错误合并；
- JSON 可以被标准解析器直接解析。

## 4. 输出 JSON 契约

模型必须只返回一个合法 JSON 对象，不得在 JSON 前后添加 Markdown、解释文字或代码围栏。

```json
{
  "schemaVersion": "1.0.0",
  "imageId": "由调用方传入，未提供时为空字符串",
  "imageAssessment": {
    "usable": true,
    "overallQuality": "GOOD",
    "garmentCount": 2,
    "issues": [],
    "retakeRecommended": false,
    "retakeAdvice": ""
  },
  "garments": [
    {
      "itemIndex": 1,
      "displayName": "浅灰色宽松短袖T恤",
      "categoryCode": "TOP",
      "categoryLabel": "上衣",
      "subCategoryCode": "T_SHIRT",
      "subCategoryLabel": "T恤",
      "targetGender": "UNISEX",
      "colors": {
        "primaryCode": "LIGHT_GRAY",
        "primaryLabel": "浅灰色",
        "secondaryCodes": [],
        "secondaryLabels": [],
        "accentCodes": [],
        "accentLabels": [],
        "tone": "NEUTRAL",
        "estimatedHex": "#B5B5B2"
      },
      "patternCode": "SOLID",
      "patternLabel": "纯色",
      "fitCode": "RELAXED",
      "fitLabel": "宽松",
      "silhouetteCode": "H_LINE",
      "silhouetteLabel": "H型",
      "lengthCode": "REGULAR",
      "lengthLabel": "常规长度",
      "materialCodes": ["COTTON_BLEND"],
      "materialLabels": ["棉混纺"],
      "fabricProperties": {
        "thickness": "LIGHT",
        "texture": ["SMOOTH", "KNITTED"],
        "stretch": "LOW",
        "drape": "MEDIUM",
        "sheen": "MATTE",
        "breathability": "MEDIUM"
      },
      "constructionDetails": {
        "collar": "CREW_NECK",
        "sleeve": "SHORT_SLEEVE",
        "closure": "PULLOVER",
        "waist": "NOT_APPLICABLE",
        "legShape": "NOT_APPLICABLE",
        "shoeCut": "NOT_APPLICABLE"
      },
      "styleCodes": ["MINIMAL", "CASUAL"],
      "styleLabels": ["简约", "休闲"],
      "seasonCodes": ["SPRING", "SUMMER"],
      "seasonLabels": ["春季", "夏季"],
      "occasionCodes": ["DAILY", "COMMUTE"],
      "occasionLabels": ["日常", "通勤"],
      "formalityLevel": 2,
      "distinctiveDetails": [],
      "visibility": {
        "visibilityStatus": "FULL",
        "visibleRatio": 0.95,
        "occludedParts": [],
        "recognitionQuality": "GOOD"
      },
      "confidence": {
        "overall": 0.89,
        "category": 0.99,
        "subCategory": 0.97,
        "color": 0.96,
        "pattern": 0.91,
        "fit": 0.84,
        "material": 0.58,
        "style": 0.74,
        "season": 0.72
      },
      "uncertainFields": ["materialCodes"],
      "evidenceNotes": [
        "短袖、圆领和无开襟结构清晰可见",
        "面料表面接近针织棉质外观，但无法从图片确认实际成分"
      ]
    }
  ],
  "ignoredObjects": [],
  "warnings": []
}
```

## 5. 通用字段规则

### 5.1 图片质量 `overallQuality`

| 编码 | 中文含义 |
| --- | --- |
| `EXCELLENT` | 清晰、完整、颜色自然 |
| `GOOD` | 可稳定识别，存在轻微缺陷 |
| `FAIR` | 可以识别，但部分属性不可靠 |
| `POOR` | 只能判断少量属性 |
| `UNUSABLE` | 无法完成有效服装识别 |

### 5.2 可见状态 `visibilityStatus`

| 编码 | 中文含义 |
| --- | --- |
| `FULL` | 基本完整可见 |
| `PARTIAL` | 局部遮挡，但主体可以识别 |
| `SEVERELY_OCCLUDED` | 严重遮挡 |
| `UNKNOWN` | 无法判断 |

### 5.3 目标款式范围 `targetGender`

| 编码 | 中文含义 |
| --- | --- |
| `MENS` | 男款倾向 |
| `WOMENS` | 女款倾向 |
| `UNISEX` | 男女通用 |
| `UNKNOWN` | 无法判断 |

当款式没有明显性别设计特征时，优先输出 `UNISEX`，不得仅根据穿着者推断服装款式范围。

## 6. 类目枚举

### 6.1 主类目

| 编码 | 中文 |
| --- | --- |
| `TOP` | 上衣 |
| `BOTTOM` | 下装 |
| `OUTERWEAR` | 外套 |
| `ONE_PIECE` | 连体装 |
| `SHOES` | 鞋子 |

### 6.2 上衣 `TOP`

| 编码 | 中文 |
| --- | --- |
| `T_SHIRT` | T恤 |
| `SHIRT` | 衬衫 |
| `BLOUSE` | 女式衬衫 |
| `POLO` | Polo衫 |
| `SWEATSHIRT` | 卫衣 |
| `HOODIE` | 连帽卫衣 |
| `SWEATER` | 毛衣 |
| `KNIT_TOP` | 针织上衣 |
| `TANK_TOP` | 背心 |
| `CAMISOLE` | 吊带上衣 |
| `VEST` | 马甲 |
| `OTHER_TOP` | 其他上衣 |
| `UNKNOWN_TOP` | 未知上衣 |

### 6.3 下装 `BOTTOM`

| 编码 | 中文 |
| --- | --- |
| `JEANS` | 牛仔裤 |
| `TROUSERS` | 西裤 |
| `CASUAL_PANTS` | 休闲裤 |
| `CARGO_PANTS` | 工装裤 |
| `SWEATPANTS` | 运动裤 |
| `LEGGINGS` | 紧身裤 |
| `SHORTS` | 短裤 |
| `DENIM_SHORTS` | 牛仔短裤 |
| `SKIRT` | 半身裙 |
| `OTHER_BOTTOM` | 其他下装 |
| `UNKNOWN_BOTTOM` | 未知下装 |

### 6.4 外套 `OUTERWEAR`

| 编码 | 中文 |
| --- | --- |
| `JACKET` | 夹克 |
| `DENIM_JACKET` | 牛仔夹克 |
| `LEATHER_JACKET` | 皮夹克 |
| `BLAZER` | 西装外套 |
| `TRENCH_COAT` | 风衣 |
| `COAT` | 大衣 |
| `DOWN_JACKET` | 羽绒服 |
| `PUFFER_JACKET` | 棉服 |
| `CARDIGAN` | 开衫 |
| `WINDBREAKER` | 防风外套 |
| `OTHER_OUTERWEAR` | 其他外套 |
| `UNKNOWN_OUTERWEAR` | 未知外套 |

### 6.5 连体装 `ONE_PIECE`

| 编码 | 中文 |
| --- | --- |
| `DRESS` | 连衣裙 |
| `JUMPSUIT` | 连体裤 |
| `OVERALLS` | 背带装 |
| `ROMPER` | 连体短裤 |
| `OTHER_ONE_PIECE` | 其他连体装 |
| `UNKNOWN_ONE_PIECE` | 未知连体装 |

### 6.6 鞋子 `SHOES`

| 编码 | 中文 |
| --- | --- |
| `SNEAKERS` | 运动鞋 |
| `RUNNING_SHOES` | 跑鞋 |
| `CANVAS_SHOES` | 帆布鞋 |
| `LOAFERS` | 乐福鞋 |
| `OXFORDS` | 牛津鞋 |
| `FLATS` | 平底鞋 |
| `HEELS` | 高跟鞋 |
| `BOOTS` | 靴子 |
| `ANKLE_BOOTS` | 短靴 |
| `SANDALS` | 凉鞋 |
| `SLIPPERS` | 拖鞋 |
| `OTHER_SHOES` | 其他鞋子 |
| `UNKNOWN_SHOES` | 未知鞋子 |

## 7. 颜色枚举

### 7.1 标准颜色

```text
BLACK          黑色
CHARCOAL       炭灰色
DARK_GRAY      深灰色
GRAY           灰色
LIGHT_GRAY     浅灰色
SILVER         银色
WHITE          白色
OFF_WHITE      米白色
CREAM          奶油色
BEIGE          米色
KHAKI          卡其色
CAMEL          驼色
BROWN          棕色
DARK_BROWN     深棕色
RED            红色
DARK_RED       深红色
BURGUNDY       酒红色
PINK           粉色
ORANGE         橙色
YELLOW         黄色
GREEN          绿色
DARK_GREEN     深绿色
OLIVE          橄榄绿
MINT           薄荷绿
BLUE           蓝色
SKY_BLUE       天蓝色
DARK_BLUE      深蓝色
NAVY           藏蓝色
BLUE_GRAY      蓝灰色
PURPLE         紫色
LAVENDER       淡紫色
GOLD           金色
MULTICOLOR     多色
UNKNOWN        未知
```

### 7.2 色调 `tone`

| 编码 | 中文 |
| --- | --- |
| `WARM` | 暖色调 |
| `COOL` | 冷色调 |
| `NEUTRAL` | 中性色调 |
| `MIXED` | 混合色调 |
| `UNKNOWN` | 未知 |

颜色规则：

- `primaryCode` 必须只有一个；
- 辅色和点缀色允许多个；
- `estimatedHex` 只能填写图片中估算的代表色，不视为真实商品色号；
- 强滤镜、彩色灯光或曝光异常时，降低颜色置信度并写入 `warnings`。

## 8. 图案枚举

```text
SOLID          纯色
STRIPED        条纹
CHECKED        格纹
PLAID          经典格子
POLKA_DOT      波点
FLORAL         花卉
GRAPHIC        图形印花
LETTER         字母印花
LOGO           标志图案
ANIMAL         动物图案
ANIMAL_PRINT   动物纹
CAMOUFLAGE     迷彩
ABSTRACT       抽象图案
GEOMETRIC      几何图案
COLOR_BLOCK    拼色
TIE_DYE        扎染
TEXTURED       肌理图案
OTHER_PATTERN  其他图案
UNKNOWN        未知
```

不得把清晰存在的印花、条纹或字母在描述中省略。对于无法辨认内容的印花，只描述图案类型和主要颜色，不编造文字内容。

## 9. 版型、轮廓与长度

### 9.1 版型 `fitCode`

```text
BODY_FIT       贴身
SLIM           修身
REGULAR        常规
RELAXED        宽松
OVERSIZED      超宽松
UNKNOWN        未知
```

### 9.2 轮廓 `silhouetteCode`

```text
H_LINE         H型
A_LINE         A型
X_LINE         X型
O_LINE         O型
STRAIGHT       直筒
TAPERED        锥形
WIDE_LEG       阔腿
FLARED         喇叭形
STRUCTURED     挺括轮廓
FLOWY          飘逸轮廓
UNKNOWN        未知
```

### 9.3 长度 `lengthCode`

通用长度：

```text
CROPPED        短款
SHORT          偏短
REGULAR        常规
LONG           长款
EXTRA_LONG     超长款
```

裙装和连衣裙可使用：

```text
MINI           短裙
KNEE_LENGTH    及膝
MIDI           中长
MAXI           长裙
```

裤装可使用：

```text
SHORTS_LENGTH  短裤长度
CROPPED_PANTS  七至九分长度
ANKLE_LENGTH   脚踝长度
FULL_LENGTH    全长
```

## 10. 材质与面料属性

### 10.1 材质枚举

```text
COTTON             棉
COTTON_BLEND       棉混纺
LINEN              亚麻
WOOL               羊毛
WOOL_BLEND         羊毛混纺
CASHMERE           羊绒
SILK               真丝
DENIM              牛仔布
LEATHER            真皮
FAUX_LEATHER       人造皮革
SUEDE              麂皮
POLYESTER          聚酯纤维
NYLON              锦纶
VISCOSE            粘胶纤维
MODAL              莫代尔
LYOCELL            莱赛尔
SPANDEX            氨纶
ACRYLIC            腈纶
SYNTHETIC_BLEND    化纤混纺
KNIT_UNSPECIFIED   未知成分针织面料
MESH               网眼面料
LACE               蕾丝
OTHER_MATERIAL     其他材质
UNKNOWN            未知
```

材质规则：

- 图片无法证明纤维成分，通常只能推测；
- 不得输出“95%棉、5%氨纶”等精确比例；
- 外观同时符合多个材质时，`materialCodes` 最多输出三个候选；
- 第一候选必须是 AI 认为最可能的材质；
- 材质置信度低于 `0.60` 时必须加入 `uncertainFields`。

### 10.2 面料属性

厚度 `thickness`：

```text
ULTRA_LIGHT  极薄
LIGHT        轻薄
MEDIUM       中等
THICK        厚实
HEAVY        很厚
UNKNOWN      未知
```

纹理 `texture`：

```text
SMOOTH          平滑
RIBBED          罗纹
KNITTED         针织
WOVEN           梭织
FLEECE          抓绒
TERRY           毛圈
DENIM_TEXTURE   牛仔纹理
LEATHER_GRAIN   皮革纹理
SUEDE_TEXTURE   麂皮纹理
LACE_TEXTURE    蕾丝纹理
MESH_TEXTURE    网眼纹理
QUILTED         绗缝
PLEATED         褶皱
OTHER_TEXTURE   其他纹理
UNKNOWN         未知
```

弹性、垂坠感、光泽和透气性：

```text
NONE / LOW / MEDIUM / HIGH / UNKNOWN
```

光泽也可以使用：

```text
MATTE / SLIGHT_SHEEN / GLOSSY / UNKNOWN
```

这些属性属于视觉推测，除非图片证据非常充分，否则置信度不得高于 `0.80`。

## 11. 结构细节

只填写与当前服装相关的字段，不适用时必须输出 `NOT_APPLICABLE`。

### 11.1 领型 `collar`

```text
CREW_NECK       圆领
V_NECK          V领
SQUARE_NECK     方领
POLO_COLLAR     Polo领
SHIRT_COLLAR    衬衫领
TURTLENECK      高领
HOODED          连帽
OFF_SHOULDER    一字肩
OTHER_COLLAR    其他领型
NO_COLLAR       无领
UNKNOWN         未知
NOT_APPLICABLE  不适用
```

### 11.2 袖型 `sleeve`

```text
SLEEVELESS       无袖
SHORT_SLEEVE     短袖
ELBOW_SLEEVE     肘长袖
THREE_QUARTER    七分袖
LONG_SLEEVE      长袖
PUFF_SLEEVE      泡泡袖
RAGLAN_SLEEVE    插肩袖
OTHER_SLEEVE     其他袖型
UNKNOWN          未知
NOT_APPLICABLE   不适用
```

### 11.3 开合方式 `closure`

```text
PULLOVER         套头
BUTTON           纽扣
ZIPPER           拉链
DOUBLE_BREASTED  双排扣
TIE              系带
ELASTIC          松紧
OPEN_FRONT       开襟
OTHER_CLOSURE    其他
UNKNOWN          未知
NOT_APPLICABLE   不适用
```

### 11.4 腰型 `waist`

```text
LOW_RISE          低腰
MID_RISE          中腰
HIGH_RISE         高腰
ELASTIC_WAIST     松紧腰
DRAWSTRING_WAIST  抽绳腰
UNKNOWN           未知
NOT_APPLICABLE    不适用
```

### 11.5 裤腿 `legShape`

```text
SKINNY           紧身
SLIM             修身
STRAIGHT         直筒
TAPERED          锥形
WIDE_LEG         阔腿
FLARED           喇叭
JOGGER           束脚
UNKNOWN          未知
NOT_APPLICABLE   不适用
```

### 11.6 鞋型 `shoeCut`

```text
LOW_TOP          低帮
MID_TOP          中帮
HIGH_TOP         高帮
ANKLE            及踝
KNEE_HIGH        及膝
OPEN_TOE         露趾
CLOSED_TOE       包趾
UNKNOWN          未知
NOT_APPLICABLE   不适用
```

## 12. 风格、季节和场景

### 12.1 风格 `styleCodes`

最多选择三个最明显的风格：

```text
MINIMAL      简约
CASUAL       休闲
COMMUTER     通勤
BUSINESS     商务
FORMAL       正式
SPORTY       运动
ATHLEISURE   运动休闲
STREET       街头
VINTAGE      复古
PREPPY       学院
WORKWEAR     工装
OUTDOOR      户外
ELEGANT      优雅
ROMANTIC     浪漫
SWEET        甜美
SEXY         性感
PUNK         朋克
Y2K          千禧
BOHEMIAN     波西米亚
OTHER_STYLE  其他
UNKNOWN      未知
```

### 12.2 季节 `seasonCodes`

```text
SPRING      春季
SUMMER      夏季
AUTUMN      秋季
WINTER      冬季
ALL_SEASON  四季
UNKNOWN     未知
```

季节可以多选。不得因为图片人物所处背景是夏天，就直接把厚外套标记为夏季服装。

### 12.3 场景 `occasionCodes`

最多选择四个合理场景：

```text
DAILY             日常
COMMUTE           通勤
WORKPLACE         职场
BUSINESS_MEETING  商务会议
FORMAL_EVENT      正式活动
DATE              约会
PARTY             聚会
TRAVEL            旅行
OUTDOOR           户外
SPORTS            运动
HOME              居家
SCHOOL            上学
OTHER_OCCASION    其他
UNKNOWN           未知
```

### 12.4 正式度 `formalityLevel`

使用 `1` 至 `5`：

| 等级 | 含义 |
| --- | --- |
| `1` | 居家、非常随意 |
| `2` | 日常休闲 |
| `3` | 通勤、轻商务 |
| `4` | 商务、半正式 |
| `5` | 正式礼仪场合 |

无法判断时使用 `0`。

## 13. 置信度规范

置信度必须使用 `0.00` 至 `1.00` 的小数，保留两位即可。

| 范围 | 含义 | 处理 |
| --- | --- | --- |
| `0.85 - 1.00` | 图像证据充分 | 可作为高可信标注 |
| `0.70 - 0.84` | 较可信 | 正常输出 |
| `0.55 - 0.69` | 合理推测 | 加入 `uncertainFields` |
| `0.40 - 0.54` | 较弱推测 | 优先使用 `UNKNOWN`，必要时保留候选 |
| `< 0.40` | 证据不足 | 必须使用 `UNKNOWN` |

`overall` 不能简单复制最高置信度，应综合考虑图片质量、遮挡程度以及关键字段可靠性。

## 14. 遮挡与重拍规则

以下情况不要求直接拒绝整张图片：

- 上衣下摆被裤腰轻微遮挡；
- 外套局部遮住内搭；
- 手臂遮住少量图案；
- 人物正常穿着造成自然褶皱；
- 鞋子存在轻微角度透视。

以下情况应建议补拍：

- 单品大部分被其他衣服覆盖；
- 关键结构完全不可见；
- 裤子只露出很短一部分；
- 鞋子只有鞋尖可见；
- 图片严重模糊或主体过小；
- 强烈滤镜导致颜色无法判断；
- 正反面信息对类别判断存在决定性影响。

补拍建议必须具体，例如：

```text
请补拍裤子正面完整照片，确保腰部、裤腿和裤脚均可见。
```

不得只返回“图片不合格”。

## 15. 强制系统提示词

下面内容可以直接作为服装标注模型的 System Prompt。调用方应把本规范允许的枚举同步给模型，不能只发送自然语言描述。

```text
你是服装图片标准化标注引擎，不是聊天助手，也不是穿搭推荐助手。

你的唯一任务是：
1. 检查输入图片是否可用于服装识别；
2. 找出图片中的全部核心服装单品；
3. 将每件上衣、下装、外套、连体装和鞋子分别标注；
4. 按指定 JSON 契约输出标准属性。

必须遵守：
- 一张图片存在多件服装时，每件单品必须是 garments 数组中的独立对象。
- 穿在人身上的服装允许识别，不要求一定为平铺图。
- 不得把整套穿搭合并成一件单品。
- 不标注包、帽子、首饰、眼镜等非当前核心类目。
- 所有 code 必须来自调用方提供的枚举，不得自创编码。
- displayName 必须使用自然、具体的中文名称，不得输出“GRAY上衣”“衣服1”等名称。
- 主色单选；风格、季节、场景和材质允许多选。
- 材质、弹性、透气性、保暖度等不可直接确认的属性允许合理推测，但必须降低 confidence，并加入 uncertainFields。
- 不得编造精确材质比例、品牌、价格、尺码和尺寸。
- 严重遮挡或无法判断的字段必须输出 UNKNOWN。
- 对于轻微遮挡，不得直接拒绝识别，应完成当前能够完成的标注。
- 不得根据人物推测身份、年龄、职业、身高、体重或敏感属性。
- 输出前检查 categoryCode 与 subCategoryCode 是否匹配。
- 最终只能返回合法 JSON，禁止添加 Markdown、解释、前言和结尾。

当图片不可用时：
- imageAssessment.usable=false；
- garments=[]；
- issues 中写明问题；
- retakeRecommended=true；
- retakeAdvice 给出具体补拍方法。
```

## 16. 多单品标注示例

输入：一名人物穿白色短袖上衣、深蓝色牛仔裤和黑白运动鞋。

简化输出示例：

```json
{
  "schemaVersion": "1.0.0",
  "imageId": "example_001",
  "imageAssessment": {
    "usable": true,
    "overallQuality": "GOOD",
    "garmentCount": 3,
    "issues": [],
    "retakeRecommended": false,
    "retakeAdvice": ""
  },
  "garments": [
    {
      "itemIndex": 1,
      "displayName": "白色宽松短袖T恤",
      "categoryCode": "TOP",
      "categoryLabel": "上衣",
      "subCategoryCode": "T_SHIRT",
      "subCategoryLabel": "T恤",
      "colors": {
        "primaryCode": "WHITE",
        "primaryLabel": "白色",
        "secondaryCodes": [],
        "secondaryLabels": []
      },
      "patternCode": "SOLID",
      "patternLabel": "纯色",
      "fitCode": "RELAXED",
      "fitLabel": "宽松",
      "styleCodes": ["MINIMAL", "CASUAL"],
      "styleLabels": ["简约", "休闲"],
      "seasonCodes": ["SPRING", "SUMMER"],
      "seasonLabels": ["春季", "夏季"],
      "confidence": {
        "overall": 0.91,
        "category": 0.99,
        "color": 0.97,
        "material": 0.56
      },
      "uncertainFields": ["materialCodes"]
    },
    {
      "itemIndex": 2,
      "displayName": "深蓝色直筒牛仔裤",
      "categoryCode": "BOTTOM",
      "categoryLabel": "下装",
      "subCategoryCode": "JEANS",
      "subCategoryLabel": "牛仔裤",
      "colors": {
        "primaryCode": "DARK_BLUE",
        "primaryLabel": "深蓝色",
        "secondaryCodes": [],
        "secondaryLabels": []
      },
      "patternCode": "SOLID",
      "patternLabel": "纯色",
      "fitCode": "REGULAR",
      "fitLabel": "常规",
      "materialCodes": ["DENIM"],
      "materialLabels": ["牛仔布"],
      "confidence": {
        "overall": 0.93,
        "category": 0.99,
        "color": 0.94,
        "material": 0.88
      },
      "uncertainFields": []
    },
    {
      "itemIndex": 3,
      "displayName": "黑白拼色低帮运动鞋",
      "categoryCode": "SHOES",
      "categoryLabel": "鞋子",
      "subCategoryCode": "SNEAKERS",
      "subCategoryLabel": "运动鞋",
      "colors": {
        "primaryCode": "BLACK",
        "primaryLabel": "黑色",
        "secondaryCodes": ["WHITE"],
        "secondaryLabels": ["白色"]
      },
      "patternCode": "COLOR_BLOCK",
      "patternLabel": "拼色",
      "confidence": {
        "overall": 0.82,
        "category": 0.93,
        "color": 0.91,
        "material": 0.42
      },
      "uncertainFields": ["materialCodes"]
    }
  ],
  "ignoredObjects": [],
  "warnings": []
}
```

正式调用时每件单品仍须返回第 4 节契约中的全部字段，示例为便于阅读进行了删减。

## 17. 不可用图片示例

```json
{
  "schemaVersion": "1.0.0",
  "imageId": "example_002",
  "imageAssessment": {
    "usable": false,
    "overallQuality": "UNUSABLE",
    "garmentCount": 0,
    "issues": [
      "人物主体过小",
      "服装区域严重模糊",
      "无法判断服装类目和主要颜色"
    ],
    "retakeRecommended": true,
    "retakeAdvice": "请在光线自然的环境下重新拍摄，使服装主体占据画面主要区域，并保证衣服轮廓清晰可见。"
  },
  "garments": [],
  "ignoredObjects": [],
  "warnings": []
}
```

## 18. 禁止行为

AI 标注模型不得：

- 把人物整套造型输出为单个服装对象；
- 只识别第一件服装，遗漏其他清晰单品；
- 把图案删除后错误标记为纯色；
- 根据模糊轮廓编造品牌、尺码或商品名称；
- 把人物性别直接当成服装 `targetGender`；
- 把材质推测写成确定事实；
- 使用规范之外的新英文编码；
- 在 JSON 之外输出解释文字；
- 使用颜色英文编码直接拼接中文展示名称；
- 因轻微遮挡而拒绝整张图片；
- 在没有证据时填写精确数值。

## 19. 标注结果验收清单

每次模型输出至少检查：

- [ ] 返回内容是合法 JSON；
- [ ] `schemaVersion` 正确；
- [ ] 图片中每件核心服装都有独立对象；
- [ ] 主类目与细分类目匹配；
- [ ] 中文名称自然、具体、可区分；
- [ ] 主色单选，辅助颜色没有重复；
- [ ] 图案没有被错误丢失；
- [ ] 材质推测带有置信度；
- [ ] 遮挡部位已记录；
- [ ] 不可见字段使用 `UNKNOWN`；
- [ ] 所有枚举均来自本规范；
- [ ] 未输出人物敏感属性；
- [ ] 未输出品牌、价格、精确尺寸等无证据内容；
- [ ] 多单品数量与 `garmentCount` 一致；
- [ ] `uncertainFields` 与低置信度字段一致。

## 20. 版本管理

所有标注结果必须保存 `schemaVersion`。

当以后新增配饰、调整枚举或修改字段含义时，必须升级版本，例如：

```text
1.0.0：核心服装单品识别
1.0.1：补充多角度来源图的正面裁切与去重清洗规则，不修改 JSON 契约
1.1.0：新增兼容枚举，不破坏旧数据
2.0.0：修改字段结构或旧枚举含义
```

不得在不修改版本号的情况下改变已有编码含义。
