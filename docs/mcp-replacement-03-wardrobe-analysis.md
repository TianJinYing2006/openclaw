# MCP 替换方案 03：服装识别（Wardrobe Photo Analysis）

> 本方案供 Trae 直接实现。所有路径均为项目相对路径，改动完成后请按文末验证清单自检。

---

## 一、现状分析

### 1.1 识别工作流

```
用户上传照片 → analyze_wardrobe_photo（工具层）
             → FashionWardrobeIngestionService.analyzePhoto()
             → 先查已有候选（DB 缓存命中则直接复用）
             → FashionVisionCandidateAnalyzer.analyze(stored)   ← 替换点
                 ├─ analyzeSavedSummary()：复用已保存的图片描述（快速路径）
                 └─ inspection.inspect()：调用 ChatCompletions 视觉模型（完整路径）
             → parse()：解析 JSON → ClothingCandidateDraft 列表
             → repository.createCandidateDrafts()：持久化候选
```

### 1.2 核心问题：无 SPI 接口

与前两个方案不同，`FashionVisionCandidateAnalyzer` 是一个**具体的 `@Service` 类**，不是接口。它承担三项职责：

1. **快速路径**：`analyzeSavedSummary()` — 复用已保存的图片视觉摘要，避免重复调用视觉 API
2. **视觉调用**：`inspection.inspect(source, ANALYSIS_PROMPT)` — 调用 `ImageInspectionService` 发图片给 ChatCompletions 视觉模型
3. **结果解析**：`parse()` — 解析 JSON 响应，执行类目归一化、质量阈值校验、显示名生成

MCP 替换需要替换第 2 步（视觉调用），同时保留第 3 步的业务规则。

### 1.3 现有实现关键细节

文件：`src/main/java/com/example/ykdsummer/fashion/application/FashionVisionCandidateAnalyzer.java`

- `@Service` 无条件注册
- 依赖 `ImageInspectionService`（视觉网关）和 `ObjectMapper`
- `analyzeSavedSummary()` 是 `chat-completions` 特有的优化：从 `StoredImage.tags()` 提取已保存摘要，用中文关键词匹配生成候选（纯本地启发式，不调用 API）
- `parse()` 包含关键业务规则：
  - `category()` — 类目归一化（`PANTS` → `STRAIGHT_PANTS`，`短袖` → `T_SHIRT` 等）
  - 质量阈值：`qualityScore < 0.45` 或类目 `UNKNOWN` → 强制 `RETAKE_REQUIRED`
  - `FashionItemNamer.nameFor()` — 生成中文显示名
- `PROMPT_VERSION = "fashion-wardrobe-v2"` — 静态常量，被外部引用

### 1.4 调用方

文件：`src/main/java/com/example/ykdsummer/fashion/application/FashionWardrobeIngestionService.java`

```java
private final FashionVisionCandidateAnalyzer analyzer;
// ...
FashionVisionCandidateAnalyzer.AnalysisResult analysis = analyzer.analyze(stored);
List<ClothingCandidate> candidates = repository.createCandidateDrafts(externalUserId, source, analysis.candidates(),
        "chat-completions-vision", "", FashionVisionCandidateAnalyzer.PROMPT_VERSION, draftDeadline());
```

**三处硬编码需要解耦：**
1. 字段类型 `FashionVisionCandidateAnalyzer` → 改为接口
2. `"chat-completions-vision"` → 改为 `analyzer.providerName()`
3. `FashionVisionCandidateAnalyzer.PROMPT_VERSION` → 改为 `analyzer.promptVersion()`

### 1.5 测试文件引用

- `FashionVisionCandidateAnalyzerTest` — 直接构造 `FashionVisionCandidateAnalyzer`，测试 `parse()` 和 `analyze()`
- `FashionVisionLiveProbeTest` — 线上探针，直接构造分析器
- `FashionOssHistoryLiveProbeTest` — OSS 历史探针，持有分析器字段

这些测试针对**具体实现**，接口提取后无需改动（它们仍然直接构造 `FashionVisionCandidateAnalyzer`）。

---

## 二、改动清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 1 | `fashion/application/WardrobePhotoAnalyzer.java` | 新建 | SPI 接口，含 `AnalysisResult` 记录 |
| 2 | `fashion/application/FashionVisionCandidateAnalyzer.java` | 修改 | 实现接口 + 加条件注解 + 暴露 metadata 方法 |
| 3 | `fashion/application/McpWardrobePhotoAnalyzer.java` | 新建 | MCP 实现，调用外部识别工具 |
| 4 | `fashion/config/FashionAnalysisProperties.java` | 新建 | 识别 provider 切换配置 |
| 5 | `fashion/application/FashionWardrobeIngestionService.java` | 修改 | 依赖接口，消除硬编码 |
| 6 | `application-fashion.properties` | 修改 | 追加识别 provider 配置项 |
| 7 | `McpWardrobePhotoAnalyzerTest.java` | 新建 | MCP 实现单元测试 |
| 8 | `WardrobePhotoAnalyzerProviderSelectionTest.java` | 新建 | provider 切换条件测试 |

---

## 三、详细实现

### 3.1 新建 `WardrobePhotoAnalyzer.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/WardrobePhotoAnalyzer.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateDraft;
import java.util.List;

/**
 * Replaceable provider boundary for analyzing a wardrobe photo into structured clothing candidates.
 *
 * <p>Each implementation owns its vision call and parsing logic, but must return candidates that
 * obey the same business rules: category normalization, quality thresholds, and display-name
 * generation via {@link FashionItemNamer}.</p>
 */
public interface WardrobePhotoAnalyzer {

    /** Analyze the source image and return structured clothing candidate drafts. */
    AnalysisResult analyze(StoredImage source);

    /** Provider identifier persisted as candidate metadata (e.g. {@code "chat-completions-vision"}). */
    default String providerName() { return "unknown"; }

    /** Prompt or analysis version persisted as candidate metadata (e.g. {@code "fashion-wardrobe-v2"}). */
    default String promptVersion() { return "unknown"; }

    /** Structured analysis result before persistence. */
    record AnalysisResult(String summary, List<ClothingCandidateDraft> candidates) { }
}
```

**设计说明：**

- `AnalysisResult` 从 `FashionVisionCandidateAnalyzer` 的嵌套记录迁移到接口中
- `providerName()` 和 `promptVersion()` 提供 metadata 给调用方持久化，避免硬编码
- 两个 metadata 方法用 `default` 实现，保持接口简洁

### 3.2 修改 `FashionVisionCandidateAnalyzer.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/FashionVisionCandidateAnalyzer.java`

**改动点：**

1. 添加 `implements WardrobePhotoAnalyzer`
2. 添加 `@ConditionalOnProperty` 注解
3. 实现 `providerName()` 和 `promptVersion()`
4. `AnalysisResult` 记录移到接口，原嵌套记录删除（或保留为 deprecated 别名）

改动前（第 19-23 行）：
```java
/** Converts a vision response into structured clothing candidate drafts for a user-confirmed wardrobe. */
@Service
public class FashionVisionCandidateAnalyzer {
    static final String PROMPT_VERSION = "fashion-wardrobe-v2";
```

改动后：
```java
/** ChatCompletions vision provider: analyzes photos via the image inspection gateway. */
@Service
@ConditionalOnProperty(prefix = "app.fashion.analysis", name = "provider",
        havingValue = "chat-completions", matchIfMissing = true)
public class FashionVisionCandidateAnalyzer implements WardrobePhotoAnalyzer {
    static final String PROMPT_VERSION = "fashion-wardrobe-v2";
```

添加 import：
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

在类体内添加两个 metadata 方法（放在 `analyze()` 方法之后）：
```java
@Override
public String providerName() { return "chat-completions-vision"; }

@Override
public String promptVersion() { return PROMPT_VERSION; }
```

删除原有的嵌套 `AnalysisResult` 记录（第 322 行）：
```java
// 删除这行：
// public record AnalysisResult(String summary, List<ClothingCandidateDraft> candidates) { }
```

`analyze()` 方法的返回类型从 `AnalysisResult` 改为 `WardrobePhotoAnalyzer.AnalysisResult`（因为 `AnalysisResult` 现在在接口上）：
```java
@Override
public WardrobePhotoAnalyzer.AnalysisResult analyze(StoredImage source) {
    // 方法体不变
}
```

`parse()` 方法同理，返回类型改为 `WardrobePhotoAnalyzer.AnalysisResult`：
```java
WardrobePhotoAnalyzer.AnalysisResult parse(String raw) {
    // 方法体不变
}
```

`analyzeSavedSummary()` 方法同理：
```java
private WardrobePhotoAnalyzer.AnalysisResult analyzeSavedSummary(String tags) {
    // 方法体不变
}
```

**`PROMPT_VERSION` 保留为 `static final`**，因为测试文件直接引用 `FashionVisionCandidateAnalyzer.PROMPT_VERSION`。但调用方 `FashionWardrobeIngestionService` 改用 `analyzer.promptVersion()`。

### 3.3 新建 `FashionAnalysisProperties.java`

路径：`src/main/java/com/example/ykdsummer/fashion/config/FashionAnalysisProperties.java`

```java
package com.example.ykdsummer.fashion.wardrobe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 服装识别 provider 切换配置。 */
@Component
@ConfigurationProperties(prefix = "app.fashion.analysis")
public class FashionAnalysisProperties {
    /**
     * 识别 provider：
     * {@code chat-completions}（默认，ChatCompletions 视觉模型）或 {@code mcp}（外部识别 MCP Server）。
     */
    private String provider = "chat-completions";
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.fashion.analysis.mcp.*}。 */
    public static class Mcp {
        /** 外部识别 MCP Server 提供的工具名。 */
        private String toolName = "wardrobe_photo_analysis";
        /** 分析版本标识，持久化为候选 metadata。 */
        private String promptVersion = "mcp-v1";
        /** MCP 调用超时（秒）；0 或负数表示使用 ImageInspectionService 默认超时。 */
        private long timeoutSeconds = 60;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.toolName = toolName;
            }
        }

        public String getPromptVersion() { return promptVersion; }
        public void setPromptVersion(String promptVersion) {
            if (promptVersion != null && !promptVersion.isBlank()) {
                this.promptVersion = promptVersion;
            }
        }

        public long getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
```

### 3.4 新建 `McpWardrobePhotoAnalyzer.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/McpWardrobePhotoAnalyzer.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import com.example.ykdsummer.ai.service.AiGatewayException;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.config.FashionAnalysisProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部服装识别 MCP Server 工具替代 ChatCompletions 视觉模型。
 *
 * <p>外部 Server 接收图片签名 URL，返回结构化候选 JSON。本实现负责解析响应并应用与
 * {@link FashionVisionCandidateAnalyzer} 相同的业务规则：类目归一化、质量阈值、显示名生成。</p>
 *
 * <p>不包含 {@code analyzeSavedSummary()} 快速路径——该优化是 ChatCompletions 特有的
 * （复用已保存的图片描述）。MCP Server 自行决定是否缓存；上层 {@code analyzePhoto()} 的
 * 候选 DB 缓存仍然生效。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.fashion.analysis", name = "provider", havingValue = "mcp")
public class McpWardrobePhotoAnalyzer implements WardrobePhotoAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(McpWardrobePhotoAnalyzer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal MINIMUM_USABLE_QUALITY = new BigDecimal("0.45");

    private final SyncMcpToolCallbackProvider toolProvider;
    private final LocalImageAssetStore imageStore;
    private final FashionAnalysisProperties properties;

    public McpWardrobePhotoAnalyzer(SyncMcpToolCallbackProvider toolProvider,
                                    LocalImageAssetStore imageStore,
                                    FashionAnalysisProperties properties) {
        this.toolProvider = toolProvider;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Override
    public WardrobePhotoAnalyzer.AnalysisResult analyze(StoredImage source) {
        if (source == null) throw new IllegalArgumentException("source image is required");
        String toolName = properties.getMcp().getToolName();
        try {
            String imageUrl = imageStore.signedReadUrl(source);
            ToolCallback tool = findTool(toolName);
            if (tool == null) {
                log.warn("Wardrobe photo analysis MCP tool not found, tool={}", toolName);
                throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
            }
            String jsonArgs = JSON.createObjectNode()
                    .put("imageUrl", imageUrl)
                    .toString();
            String raw = tool.call(jsonArgs);
            return parse(raw);
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Wardrobe photo analysis MCP call failed, tool={}, type={}", toolName,
                    exception.getClass().getSimpleName(), exception);
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
        }
    }

    @Override
    public String providerName() { return "mcp"; }

    @Override
    public String promptVersion() { return properties.getMcp().getPromptVersion(); }

    private ToolCallback findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        for (ToolCallback callback : toolProvider.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    /**
     * Parses the MCP result JSON into candidate drafts.
     *
     * <p>Applies the same business rules as {@link FashionVisionCandidateAnalyzer#parse}:
     * category normalization, quality threshold enforcement, and display-name generation
     * via {@link FashionItemNamer}.</p>
     */
    WardrobePhotoAnalyzer.AnalysisResult parse(String raw) {
        try {
            JsonNode root = JSON.readTree(extractJson(raw));
            JsonNode candidates = root != null && root.isArray() ? root : root.path("candidates");
            if (!candidates.isArray()) {
                return new WardrobePhotoAnalyzer.AnalysisResult("Unable to read structured clothing candidates", List.of());
            }
            List<ClothingCandidateDraft> drafts = new ArrayList<>();
            int index = 0;
            for (JsonNode node : candidates) {
                if (!node.isObject() || drafts.size() >= 8) continue;
                drafts.add(draft(index++, node));
            }
            String summary = root != null && root.isObject() ? text(root.path("summary").asText(), 512) : "";
            return new WardrobePhotoAnalyzer.AnalysisResult(summary, List.copyOf(drafts));
        } catch (JsonProcessingException exception) {
            return new WardrobePhotoAnalyzer.AnalysisResult("Vision response was not valid structured JSON", List.of());
        }
    }

    private ClothingCandidateDraft draft(int index, JsonNode node) throws JsonProcessingException {
        BigDecimal confidence = score(node.path("confidence"));
        BigDecimal quality = score(node.path("qualityScore"));
        ClothingCompletenessStatus completeness = completeness(node.path("completenessStatus").asText());
        String category = category(node.path("categoryCode").asText());
        String guidance = text(node.path("retakeGuidance").asText(), 512);
        if (category.equals("UNKNOWN") || quality.compareTo(MINIMUM_USABLE_QUALITY) < 0) {
            completeness = ClothingCompletenessStatus.RETAKE_REQUIRED;
            if (guidance.isBlank()) guidance = "请补拍这件衣物的主要轮廓，确保类别、颜色和大部分形状清晰可见。";
        }
        JsonNode attributes = node.path("attributes");
        String attributesJson = attributes.isObject() ? JSON.writeValueAsString(attributes) : "{}";
        String rawName = text(node.path("displayName").asText(), 128);
        String fit = text(node.path("fitCode").asText(), 64);
        String pattern = attributes.path("patternCode").asText();
        String color = text(node.path("colorPrimary").asText(), 64);
        String displayName = FashionItemNamer.nameFor(category, color, rawName + " " + attributes, fit, pattern);
        return new ClothingCandidateDraft(index, displayName, category, color, strings(node.path("secondaryColors")),
                strings(node.path("styleTags")), fit, strings(node.path("seasonTags")),
                attributesJson, confidence, quality, completeness, guidance);
    }

    // --- 以下工具方法与 FashionVisionCandidateAnalyzer 中的实现保持一致 ---
    // 业务规则必须跨 provider 统一：类目归一化、质量阈值、文本截断

    private static ClothingCompletenessStatus completeness(String value) {
        try {
            return ClothingCompletenessStatus.valueOf(text(value, 32).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ClothingCompletenessStatus.RETAKE_REQUIRED;
        }
    }

    private static String category(String value) {
        return switch (text(value, 64).toUpperCase(Locale.ROOT)) {
            case "T_SHIRT", "TEE", "TOP", "T恤", "短袖", "上衣" -> "T_SHIRT";
            case "SHIRT", "衬衫" -> "SHIRT";
            case "KNITWEAR", "针织衫", "毛衣" -> "KNITWEAR";
            case "JACKET", "OUTERWEAR", "夹克", "外套" -> "JACKET";
            case "JEANS", "牛仔裤" -> "JEANS";
            case "STRAIGHT_PANTS", "BOTTOM", "PANTS", "TROUSERS", "直筒裤", "裤子", "长裤" -> "STRAIGHT_PANTS";
            case "SKIRT", "半身裙", "裙子" -> "SKIRT";
            case "DRESS", "连衣裙" -> "DRESS";
            case "SHOES", "鞋", "鞋子" -> "SHOES";
            case "BAG", "包", "包袋" -> "BAG";
            case "ACCESSORY", "配饰" -> "ACCESSORY";
            default -> "UNKNOWN";
        };
    }

    private static BigDecimal score(JsonNode node) {
        if (!node.isNumber()) return BigDecimal.ZERO;
        BigDecimal value = node.decimalValue();
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ZERO : value;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(text(value.asText(), 128)); });
        return List.copyOf(values);
    }

    private static String extractJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).strip();
        }
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        int start = objectStart < 0 ? arrayStart : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        if (start < 0) return "{}";
        int objectEnd = value.lastIndexOf('}');
        int arrayEnd = value.lastIndexOf(']');
        int end = Math.max(objectEnd, arrayEnd);
        return end >= start ? value.substring(start, end + 1) : "{}";
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
```

**设计说明：**

1. **不包含 `analyzeSavedSummary()`**：该快速路径是 `chat-completions` 特有的优化（复用 `ImageInspectionService` 已保存的图片描述）。MCP Server 接收图片 URL 后自行分析，不需要这个优化。上层 `analyzePhoto()` 的候选 DB 缓存仍防止重复分析。

2. **复用业务规则**：`category()`、`completeness()`、`score()`、`draft()` 等方法与 `FashionVisionCandidateAnalyzer` 中的实现完全一致，确保跨 provider 的数据质量统一。这些方法有意重复而非提取为共享工具类，因为：
   - 两个实现可能独立演化（MCP Server 未来可能返回不同的 JSON 结构）
   - 提取共享类会增加一个文件和导入依赖
   - 方法体较短，维护成本低

3. **异常处理对齐**：`analyze()` 在工具未找到或调用异常时抛出 `AiGatewayException(TEMPORARY_UNAVAILABLE)`，与现有 `FashionVisionCandidateAnalyzer` 的行为一致（`inspection.inspect()` 返回不可用回复时也抛此异常）。

4. **`promptVersion()` 可配置**：MCP Server 可能有自己的版本标识，通过 `app.fashion.analysis.mcp.prompt-version` 配置，默认 `"mcp-v1"`。

### 3.5 修改 `FashionWardrobeIngestionService.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/FashionWardrobeIngestionService.java`

**改动 1：字段类型改为接口（第 43 行）**

改动前：
```java
private final FashionVisionCandidateAnalyzer analyzer;
```

改动后：
```java
private final WardrobePhotoAnalyzer analyzer;
```

**改动 2：构造函数参数类型改为接口（第 54 行）**

改动前：
```java
FashionVisionCandidateAnalyzer analyzer,
```

改动后：
```java
WardrobePhotoAnalyzer analyzer,
```

**改动 3：`analyzePhoto()` 方法中消除硬编码（第 95-97 行）**

改动前：
```java
FashionVisionCandidateAnalyzer.AnalysisResult analysis = analyzer.analyze(stored);
List<ClothingCandidate> candidates = repository.createCandidateDrafts(externalUserId, source, analysis.candidates(),
        "chat-completions-vision", "", FashionVisionCandidateAnalyzer.PROMPT_VERSION, draftDeadline());
```

改动后：
```java
WardrobePhotoAnalyzer.AnalysisResult analysis = analyzer.analyze(stored);
List<ClothingCandidate> candidates = repository.createCandidateDrafts(externalUserId, source, analysis.candidates(),
        analyzer.providerName(), "", analyzer.promptVersion(), draftDeadline());
```

**改动 4：移除不再需要的 import**

如果 `FashionVisionCandidateAnalyzer` 不再被直接引用（字段和构造函数都改成了接口），可以移除其 import。但 `PROMPT_VERSION` 常量仍被测试引用，所以类本身保留。

实际上检查一下：改动后 `FashionWardrobeIngestionService` 还引用 `FashionVisionCandidateAnalyzer` 吗？
- 字段：`WardrobePhotoAnalyzer analyzer` — 不引用
- 构造函数：`WardrobePhotoAnalyzer analyzer` — 不引用
- `analyzePhoto()`：`WardrobePhotoAnalyzer.AnalysisResult` + `analyzer.providerName()` + `analyzer.promptVersion()` — 不引用

所以可以移除 `FashionVisionCandidateAnalyzer` 的 import（如果有的话）。但检查现有代码，`FashionVisionCandidateAnalyzer` 和 `FashionWardrobeIngestionService` 在同一个包 `fashion.wardrobe.application`，不需要 import。

### 3.6 修改 `application-fashion.properties`

路径：`src/main/resources/application-fashion.properties`

在文件末尾追加：

```properties

# ====================================================================
# 服装识别 provider 切换：chat-completions（默认，ChatCompletions 视觉模型）或 mcp（外部识别 MCP Server）。
# ====================================================================
app.fashion.analysis.provider=${FASHION_ANALYSIS_PROVIDER:chat-completions}
# MCP 模式下的工具名
app.fashion.analysis.mcp.tool-name=${FASHION_ANALYSIS_MCP_TOOL_NAME:wardrobe_photo_analysis}
# MCP 分析版本标识（持久化为候选 metadata）
app.fashion.analysis.mcp.prompt-version=${FASHION_ANALYSIS_MCP_PROMPT_VERSION:mcp-v1}
# MCP 调用超时（秒）
app.fashion.analysis.mcp.timeout-seconds=${FASHION_ANALYSIS_MCP_TIMEOUT_SECONDS:60}
# MCP Client 连接外部识别 MCP Server（SSE 传输）。
# 仅当 provider=mcp 时启用；取消下面两行注释并确保外部识别服务已启动，
# 否则应用启动时会因连接不到识别服务而失败。
# 注意：如果试衣/抠图/识别共用同一个 MCP Server，只需保留一组 SSE 连接即可。
# spring.ai.mcp.client.sse.connections.analysis-mcp.url=http://localhost:8092
# spring.ai.mcp.client.sse.enabled=true
```

### 3.7 新建 `McpWardrobePhotoAnalyzerTest.java`

路径：`src/test/java/com/example/ykdsummer/fashion/application/McpWardrobePhotoAnalyzerTest.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiGatewayException;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.config.FashionAnalysisProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpWardrobePhotoAnalyzerTest {

    private final FashionAnalysisProperties properties = new FashionAnalysisProperties();
    private final LocalImageAssetStore imageStore = mock(LocalImageAssetStore.class);
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final McpWardrobePhotoAnalyzer analyzer =
            new McpWardrobePhotoAnalyzer(provider, imageStore, properties);

    @Test
    void parsesStructuredCandidatesAndForcesRetakeForLowQualityGarment() {
        ToolCallback tool = tool("wardrobe_photo_analysis", """
                {"summary":"two garments","candidates":[
                  {"displayName":"white shirt","categoryCode":"SHIRT","colorPrimary":"WHITE",
                   "secondaryColors":[],"styleTags":["MINIMAL"],"fitCode":"RELAXED","seasonTags":["SPRING"],
                   "attributes":{"patternCode":"SOLID"},"confidence":0.94,"qualityScore":0.88,
                   "completenessStatus":"READY","retakeGuidance":""},
                  {"displayName":"covered trousers","categoryCode":"PANTS","colorPrimary":"BLACK",
                   "secondaryColors":[],"styleTags":[],"fitCode":"","seasonTags":[],"attributes":{},
                   "confidence":0.71,"qualityScore":0.42,"completenessStatus":"READY","retakeGuidance":""}
                ]}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(source);

        assertThat(result.summary()).isEqualTo("two garments");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).categoryCode()).isEqualTo("SHIRT");
        assertThat(result.candidates().get(0).completenessStatus()).isEqualTo(ClothingCompletenessStatus.READY);
        assertThat(result.candidates().get(1).categoryCode()).isEqualTo("STRAIGHT_PANTS");
        assertThat(result.candidates().get(1).completenessStatus()).isEqualTo(ClothingCompletenessStatus.RETAKE_REQUIRED);
        assertThat(result.candidates().get(1).retakeGuidance()).contains("主要轮廓");
    }

    @Test
    void stripsMarkdownCodeFencesBeforeParsing() {
        ToolCallback tool = tool("wardrobe_photo_analysis", """
                ```json
                {"summary":"one item","candidates":[
                  {"displayName":"blue jacket","categoryCode":"JACKET","colorPrimary":"BLUE",
                   "secondaryColors":[],"styleTags":[],"fitCode":"","seasonTags":[],
                   "attributes":{},"confidence":0.8,"qualityScore":0.7,
                   "completenessStatus":"READY","retakeGuidance":""}
                ]}
                ```""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(source);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).categoryCode()).isEqualTo("JACKET");
    }

    @Test
    void throwsTemporaryUnavailableWhenToolIsNotExposed() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        assertThatThrownBy(() -> analyzer.analyze(source))
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    void throwsTemporaryUnavailableWhenTheCallFails() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("wardrobe_photo_analysis");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("timeout"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        assertThatThrownBy(() -> analyzer.analyze(source))
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    void returnsEmptyCandidatesWhenResponseIsMalformed() {
        ToolCallback tool = tool("wardrobe_photo_analysis", "not-json");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(source);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.summary()).contains("not valid structured JSON");
    }

    @Test
    void capsCandidateCountAtEight() {
        StringBuilder json = new StringBuilder("{\"summary\":\"many\",\"candidates\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) json.append(",");
            json.append("{\"displayName\":\"item ").append(i)
                    .append("\",\"categoryCode\":\"T_SHIRT\",\"colorPrimary\":\"WHITE\",")
                    .append("\"secondaryColors\":[],\"styleTags\":[],\"fitCode\":\"\",")
                    .append("\"seasonTags\":[],\"attributes\":{},\"confidence\":0.8,")
                    .append("\"qualityScore\":0.7,\"completenessStatus\":\"READY\",\"retakeGuidance\":\"\"}");
        }
        json.append("]}");
        ToolCallback tool = tool("wardrobe_photo_analysis", json.toString());
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/photo.png");

        WardrobePhotoAnalyzer.AnalysisResult result = analyzer.analyze(source);

        assertThat(result.candidates()).hasSize(8);
    }

    @Test
    void providerNameAndPromptVersionAreExposed() {
        assertThat(analyzer.providerName()).isEqualTo("mcp");
        assertThat(analyzer.promptVersion()).isEqualTo("mcp-v1");

        properties.getMcp().setPromptVersion("external-v2");
        assertThat(analyzer.promptVersion()).isEqualTo("external-v2");
    }

    @Test
    void rejectsNullSourceImage() {
        assertThatThrownBy(() -> analyzer.analyze(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private static StoredImage sourceImage() {
        return new StoredImage("img_source", 1, Path.of("source.png"), "", null,
                Instant.now(), "image/png", "uploaded", "");
    }

    private static ToolCallback tool(String name, String response) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(response);
        return tool;
    }
}
```

### 3.8 新建 `WardrobePhotoAnalyzerProviderSelectionTest.java`

路径：`src/test/java/com/example/ykdsummer/fashion/application/WardrobePhotoAnalyzerProviderSelectionTest.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.config.FashionAnalysisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 验证 provider 切换：同一时刻只有一个 WardrobePhotoAnalyzer 实现处于激活状态。 */
class WardrobePhotoAnalyzerProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WardrobePhotoAnalyzerContext.class);

    @Test
    void defaultsToChatCompletionsProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FashionVisionCandidateAnalyzer.class);
            assertThat(context).doesNotHaveBean(McpWardrobePhotoAnalyzer.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.fashion.analysis.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpWardrobePhotoAnalyzer.class);
            assertThat(context).doesNotHaveBean(FashionVisionCandidateAnalyzer.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({FashionVisionCandidateAnalyzer.class, McpWardrobePhotoAnalyzer.class})
    static class WardrobePhotoAnalyzerContext {
        @Bean
        ImageInspectionService imageInspectionService() { return mock(ImageInspectionService.class); }

        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        LocalImageAssetStore localImageAssetStore() { return mock(LocalImageAssetStore.class); }

        @Bean
        SyncMcpToolCallbackProvider toolProvider() { return mock(SyncMcpToolCallbackProvider.class); }

        @Bean
        FashionAnalysisProperties fashionAnalysisProperties() { return new FashionAnalysisProperties(); }
    }
}
```

---

## 四、外部 MCP Server 工具契约

实施本方案前，外部识别 MCP Server 需暴露以下工具（工具名可配置）：

### 4.1 `wardrobe_photo_analysis` 工具

**入参（JSON）：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `imageUrl` | string | 是 | 源图签名 URL，Server 自行下载分析 |

**返回（JSON）：**

```json
{
  "summary": "一张照片中识别到两件可提取的服装",
  "candidates": [
    {
      "displayName": "白色宽松T恤",
      "categoryCode": "T_SHIRT",
      "colorPrimary": "WHITE",
      "secondaryColors": [],
      "styleTags": ["CASUAL"],
      "fitCode": "RELAXED",
      "seasonTags": ["SUMMER"],
      "attributes": {
        "patternCode": "SOLID",
        "material": "COTTON",
        "occasionTags": ["CASUAL"]
      },
      "confidence": 0.92,
      "qualityScore": 0.85,
      "completenessStatus": "READY",
      "retakeGuidance": ""
    }
  ]
}
```

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `summary` | string | 整体描述（中文，≤512 字符） |
| `candidates` | array | 候选列表，最多 8 个 |
| `candidates[].categoryCode` | string | 类目，可选值见下方枚举 |
| `candidates[].completenessStatus` | string | `READY` / `RETAKE_REQUIRED` / `UNSUPPORTED` |
| `candidates[].confidence` | number | 识别置信度 0-1 |
| `candidates[].qualityScore` | number | 质量分 0-1；低于 0.45 会被强制设为 `RETAKE_REQUIRED` |
| `candidates[].attributes` | object | 扩展属性 JSON，可含 `patternCode`、`material`、`occasionTags` 等 |

**类目枚举：** `T_SHIRT`、`SHIRT`、`KNITWEAR`、`JACKET`、`JEANS`、`STRAIGHT_PANTS`、`SKIRT`、`DRESS`、`SHOES`、`BAG`、`ACCESSORY`（也接受中文别名和变体如 `PANTS`、`OUTERWEAR`，本地会归一化）。

**重要：** 即使 Server 返回了 `categoryCode=UNKNOWN` 或 `qualityScore < 0.45`，本地解析仍会强制设为 `RETAKE_REQUIRED` 并补充重拍指引。这是跨 provider 的业务规则保障。

---

## 五、与其他 MCP 的关系

### 5.1 三组 MCP 工具共存

| 功能 | provider 配置前缀 | 默认工具名 | 可共用 Server |
|------|-------------------|-----------|--------------|
| 虚拟试衣 | `app.fashion.tryon` | `virtual_try_on` | 是 |
| 服装抠图 | `app.fashion.cutout` | `garment_cutout` / `garment_revise` | 是 |
| 服装识别 | `app.fashion.analysis` | `wardrobe_photo_analysis` | 是 |

三个功能可以共用同一个 MCP Server（同一组 SSE 连接），也可以分别独立部署。Spring AI 的 `SyncMcpToolCallbackProvider` 自动合并所有连接的工具，`findTool()` 按工具名查找。

### 5.2 SSE 连接配置策略

**方案 A：共用一个 Server（开发期推荐）**
```properties
spring.ai.mcp.client.sse.connections.fashion-mcp.url=http://localhost:8090
spring.ai.mcp.client.sse.enabled=true
```
一个 Server 暴露 `virtual_try_on` + `garment_cutout` + `garment_revise` + `wardrobe_photo_analysis` 四个工具。

**方案 B：独立三个 Server（生产期推荐）**
```properties
spring.ai.mcp.client.sse.connections.tryon-mcp.url=http://host-a:8090
spring.ai.mcp.client.sse.connections.cutout-mcp.url=http://host-b:8091
spring.ai.mcp.client.sse.connections.analysis-mcp.url=http://host-c:8092
spring.ai.mcp.client.sse.enabled=true
```

### 5.3 三个 provider 独立切换

三个功能可以独立配置 provider，互不影响：

```properties
app.fashion.tryon.provider=mcp         # 试衣走 MCP
app.fashion.cutout.provider=reference-image  # 抠图仍走通用图片编辑
app.fashion.analysis.provider=chat-completions  # 识别仍走 ChatCompletions
```

---

## 六、改动影响范围

### 6.1 零影响项（完全不变）

- `FashionWardrobeIntakeTools` — 无改动（工具层不感知 analyzer 类型）
- `ImageInspectionService` — 无改动（仍被 `FashionVisionCandidateAnalyzer` 和 `FashionPersonTemplateAnalyzer` 使用）
- `ClothingCandidateDraft` — 无改动
- `FashionPersonTemplateAnalyzer` — 无改动（独立的类，使用自己的 `ImageInspectionService`）
- 所有 persistence / domain / runtime 层 — 无改动

### 6.2 测试兼容性

| 测试文件 | 影响 | 说明 |
|----------|------|------|
| `FashionVisionCandidateAnalyzerTest` | 无改动 | 直接构造 `FashionVisionCandidateAnalyzer`，测试 `parse()` 和 `analyze()`。`parse()` 返回类型从 `FashionVisionCandidateAnalyzer.AnalysisResult` 变为 `WardrobePhotoAnalyzer.AnalysisResult`，但 Java 编译器自动适配（接口嵌套记录）。如果编译报错，将测试中的 `FashionVisionCandidateAnalyzer.AnalysisResult` 改为 `WardrobePhotoAnalyzer.AnalysisResult` 即可。 |
| `FashionVisionLiveProbeTest` | 同上 | |
| `FashionOssHistoryLiveProbeTest` | 同上 | |

### 6.3 行为兼容性

| 场景 | provider=chat-completions（默认） | provider=mcp |
|------|----------------------------------|--------------|
| 首次分析 | ChatCompletions 视觉模型 | 外部 MCP `wardrobe_photo_analysis` |
| 已保存摘要复用 | `analyzeSavedSummary()` 快速路径 | 不适用（每次都调 MCP Server） |
| 候选 DB 缓存命中 | 直接复用，不调 analyzer | 直接复用，不调 analyzer（完全一致） |
| 类目归一化 | `parse()` 中的 `category()` | `parse()` 中的相同 `category()` |
| 质量阈值 | `< 0.45` → `RETAKE_REQUIRED` | 相同 |
| 显示名生成 | `FashionItemNamer.nameFor()` | 相同 |
| metadata 持久化 | `provider=chat-completions-vision`, `promptVersion=fashion-wardrobe-v2` | `provider=mcp`, `promptVersion=mcp-v1`（可配置） |

**切换 provider 不影响候选持久化、标签编辑、抠图提交、草稿管理等任何后续流程。** 已有的候选在切换后仍可正常操作。

---

## 七、验证清单

完成实现后逐项自检：

- [ ] `WardrobePhotoAnalyzer` 接口编译通过，含 `analyze()`、`providerName()`、`promptVersion()` 和 `AnalysisResult` 记录
- [ ] `FashionVisionCandidateAnalyzer` 添加了 `implements WardrobePhotoAnalyzer` 和 `@ConditionalOnProperty(matchIfMissing=true)`
- [ ] `FashionVisionCandidateAnalyzer` 的 `providerName()` 返回 `"chat-completions-vision"`，`promptVersion()` 返回 `PROMPT_VERSION`
- [ ] `FashionVisionCandidateAnalyzer` 的嵌套 `AnalysisResult` 记录已删除，`analyze()` 和 `parse()` 返回类型改为 `WardrobePhotoAnalyzer.AnalysisResult`
- [ ] `McpWardrobePhotoAnalyzer` 添加了 `@ConditionalOnProperty(havingValue = "mcp")`
- [ ] `McpWardrobePhotoAnalyzer` 的 `providerName()` 返回 `"mcp"`，`promptVersion()` 返回配置值
- [ ] `FashionWardrobeIngestionService` 的字段类型改为 `WardrobePhotoAnalyzer`，构造函数参数类型改为 `WardrobePhotoAnalyzer`
- [ ] `FashionWardrobeIngestionService.analyzePhoto()` 中 `"chat-completions-vision"` 改为 `analyzer.providerName()`，`FashionVisionCandidateAnalyzer.PROMPT_VERSION` 改为 `analyzer.promptVersion()`
- [ ] 默认配置下只有 `FashionVisionCandidateAnalyzer` 激活
- [ ] 设置 `app.fashion.analysis.provider=mcp` 后只有 `McpWardrobePhotoAnalyzer` 激活
- [ ] `application-fashion.properties` 追加了 4 行配置项 + SSE 注释
- [ ] `McpWardrobePhotoAnalyzerTest` 8 个测试全部通过
- [ ] `WardrobePhotoAnalyzerProviderSelectionTest` 2 个测试全部通过
- [ ] 原有 `FashionVisionCandidateAnalyzerTest` 仍通过（如编译报错，将 `AnalysisResult` 引用改为接口版本）
- [ ] `FashionPersonTemplateAnalyzer` 未被误改（它是独立的类）
- [ ] 项目整体 `mvn compile` 通过（如果 Maven 可用）
