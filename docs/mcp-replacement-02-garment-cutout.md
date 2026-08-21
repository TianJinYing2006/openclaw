# MCP 替换方案 02：服装抠图（Garment Cutout）

> 本方案供 Trae 直接实现。所有路径均为项目相对路径，改动完成后请按文末验证清单自检。

---

## 一、现状分析

### 1.1 抠图工作流

```
用户上传照片 → analyze_wardrobe_photo（识别候选）
             → submit_garment_cutout（用户选择后提交）
             → MySQL 持久化任务（PENDING）
             → FashionGarmentCutoutDispatcher 定时轮询（3s）
             → ImageTaskRunner 有界线程池并发执行
             → FashionWardrobeIngestionService.executeCutoutTask()
             → GarmentCutoutService.cutout() 或 .revise()   ← 替换点
             → 保存生成图 → 发布完成事件 → 微信推送草稿
```

### 1.2 现有 SPI 接口（已存在，无需新建）

文件：`src/main/java/com/example/ykdsummer/fashion/application/GarmentCutoutService.java`

```java
public interface GarmentCutoutService {
    CutoutResult cutout(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction);

    default CutoutResult revise(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        return cutout(externalUserId, source, candidate, instruction);
    }

    record CutoutResult(byte[] imageBytes, String remoteUrl, String failureSummary) {
        public static CutoutResult image(byte[] bytes, String remoteUrl) { ... }
        public static CutoutResult failed(String summary) { ... }
        public boolean hasImage() { ... }
    }
}
```

**关键发现：接口已存在且是纯净的 provider 边界。** 与网页搜索不同，本方案无需新建接口，只需：
1. 给现有实现加条件注解
2. 新建 MCP 实现
3. 新建配置类
4. 补充配置项

### 1.3 现有实现

文件：`src/main/java/com/example/ykdsummer/fashion/application/ReferenceImageGarmentCutoutService.java`

- 当前用 `@Service` 无条件注册，没有 `@ConditionalOnProperty`
- 内部调用 `AiImageGenerationService.revise()` 走通用图片编辑端点
- `cutout()` 方法：从原始照片提取单品，构造英文 prompt 指定去除背景/人物/其他衣物
- `revise()` 方法：基于已有草稿图做局部修改，保留身份特征只改用户指定部分

### 1.4 调用方

文件：`src/main/java/com/example/ykdsummer/fashion/application/FashionWardrobeIngestionService.java`

`executeCutoutTask()` 方法中的调用逻辑：

```java
boolean isRevision = work.task().sourceAssetVersionId() != work.candidate().sourceAssetVersionId();
GarmentCutoutService.CutoutResult result = isRevision
        ? cutouts.revise(work.externalUserId(), source, work.candidate(), work.task().instructionText())
        : cutouts.cutout(work.externalUserId(), source, work.candidate(), work.task().instructionText());
```

- `isRevision=true`：源图版本与候选原始版本不同 → 调 `revise()`（基于草稿修改）
- `isRevision=false`：源图就是原始上传照片 → 调 `cutout()`（从原图提取）

**调用方无需任何改动**——它只依赖 `GarmentCutoutService` 接口，Spring 自动注入当前激活的实现。

---

## 二、改动清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 1 | `fashion/config/FashionCutoutProperties.java` | 新建 | 抠图 provider 切换配置 |
| 2 | `fashion/application/ReferenceImageGarmentCutoutService.java` | 修改 | 加 `@ConditionalOnProperty` 条件注解 |
| 3 | `fashion/application/McpGarmentCutoutService.java` | 新建 | MCP 实现调用外部抠图工具 |
| 4 | `application-fashion.properties` | 修改 | 追加抠图 provider 配置项 |
| 5 | `McpGarmentCutoutServiceTest.java` | 新建 | MCP 实现单元测试 |
| 6 | `GarmentCutoutProviderSelectionTest.java` | 新建 | provider 切换条件测试 |

---

## 三、详细实现

### 3.1 新建 `FashionCutoutProperties.java`

路径：`src/main/java/com/example/ykdsummer/fashion/config/FashionCutoutProperties.java`

```java
package com.example.ykdsummer.fashion.wardrobe.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 服装抠图 provider 切换配置。 */
@Component
@ConfigurationProperties(prefix = "app.fashion.cutout")
public class FashionCutoutProperties {
    /**
     * 抠图 provider：
     * {@code reference-image}（默认，通用图片编辑端点）或 {@code mcp}（外部抠图 MCP Server）。
     */
    private String provider = "reference-image";
    /** 抠图调用超时上限。 */
    private Duration providerTimeout = Duration.ofSeconds(120);
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Duration getProviderTimeout() { return providerTimeout; }
    public void setProviderTimeout(Duration providerTimeout) {
        if (providerTimeout != null && !providerTimeout.isNegative() && !providerTimeout.isZero()) {
            this.providerTimeout = providerTimeout;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.fashion.cutout.mcp.*}。 */
    public static class Mcp {
        /** 外部抠图 MCP Server 提供的工具名。首次抠图工具。 */
        private String cutoutToolName = "garment_cutout";
        /** 外部抠图 MCP Server 提供的工具名。草稿修订工具；为空时回退到 cutoutToolName。 */
        private String reviseToolName = "garment_revise";
        /** MCP 调用超时；{@code null} 时复用 {@link FashionCutoutProperties#providerTimeout}。 */
        private Duration timeout;

        public String getCutoutToolName() { return cutoutToolName; }
        public void setCutoutToolName(String cutoutToolName) {
            if (cutoutToolName != null && !cutoutToolName.isBlank()) {
                this.cutoutToolName = cutoutToolName;
            }
        }

        public String getReviseToolName() { return reviseToolName; }
        public void setReviseToolName(String reviseToolName) {
            if (reviseToolName != null && !reviseToolName.isBlank()) {
                this.reviseToolName = reviseToolName;
            }
        }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) {
            if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
                this.timeout = timeout;
            }
        }
    }
}
```

**设计说明：**

- 遵循 `FashionTryOnProperties` 的结构模式（`@Component` + `@ConfigurationProperties`，内嵌 `Mcp` 静态类）
- 提供 `cutoutToolName` 和 `reviseToolName` 两个工具名配置：
  - 外部 MCP Server 可以用一个工具同时处理抠图和修订（此时 `reviseToolName` 留空或与 `cutoutToolName` 相同，代码自动回退）
  - 也可以用两个独立工具分别处理（更常见的场景，因为抠图和修订的语义不同）
- `providerTimeout` 默认 120s（比试衣的 150s 短，因为抠图通常比试衣快）

### 3.2 修改 `ReferenceImageGarmentCutoutService.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/ReferenceImageGarmentCutoutService.java`

**唯一改动：** 添加 `@ConditionalOnProperty` 注解，使该 Bean 仅在 `provider=reference-image` 或未配置时激活。

改动前（第 10-11 行）：
```java
@Service
public class ReferenceImageGarmentCutoutService implements GarmentCutoutService {
```

改动后：
```java
@Service
@ConditionalOnProperty(prefix = "app.fashion.cutout", name = "provider",
        havingValue = "reference-image", matchIfMissing = true)
public class ReferenceImageGarmentCutoutService implements GarmentCutoutService {
```

同时添加 import：
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

**现有 `cutout()` 和 `revise()` 方法体完全不变。**

### 3.3 新建 `McpGarmentCutoutService.java`

路径：`src/main/java/com/example/ykdsummer/fashion/application/McpGarmentCutoutService.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.config.FashionCutoutProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部抠图 MCP Server 工具替代通用图片编辑端点。
 *
 * <p>外部 Server 接收源图签名 URL 和候选元数据，返回抠图结果图片（URL 或 Base64）。
 * 抠图与修订分别映射到两个可配置的工具名；修订工具名未配置时回退到抠图工具。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.fashion.cutout", name = "provider", havingValue = "mcp")
public class McpGarmentCutoutService implements GarmentCutoutService {
    private static final Logger log = LoggerFactory.getLogger(McpGarmentCutoutService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final HttpClient IMAGE_DOWNLOAD_CLIENT = HttpClient.newBuilder().build();

    private final SyncMcpToolCallbackProvider toolProvider;
    private final LocalImageAssetStore imageStore;
    private final FashionCutoutProperties properties;

    public McpGarmentCutoutService(SyncMcpToolCallbackProvider toolProvider,
                                   LocalImageAssetStore imageStore,
                                   FashionCutoutProperties properties) {
        this.toolProvider = toolProvider;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Override
    public CutoutResult cutout(String externalUserId, StoredImage source, ClothingCandidate candidate, String instruction) {
        if (source == null || candidate == null) {
            return CutoutResult.failed("抠图源图片或候选信息缺失");
        }
        return callExternalTool(externalUserId, source, candidate, instruction,
                properties.getMcp().getCutoutToolName(), "cutout");
    }

    @Override
    public CutoutResult revise(String externalUserId, StoredImage draft, ClothingCandidate candidate, String instruction) {
        if (draft == null || candidate == null) {
            return CutoutResult.failed("草稿图片或候选信息缺失");
        }
        String reviseTool = properties.getMcp().getReviseToolName();
        if (reviseTool == null || reviseTool.isBlank()) {
            reviseTool = properties.getMcp().getCutoutToolName();
        }
        return callExternalTool(externalUserId, draft, candidate, instruction, reviseTool, "revise");
    }

    private CutoutResult callExternalTool(String externalUserId, StoredImage sourceImage,
                                          ClothingCandidate candidate, String instruction,
                                          String toolName, String operation) {
        try {
            String sourceImageUrl = imageStore.signedReadUrl(sourceImage);
            ToolCallback tool = findTool(toolName);
            if (tool == null) {
                log.warn("Garment cutout MCP tool not found, user={}, operation={}, tool={}",
                        anonymize(externalUserId), operation, toolName);
                return CutoutResult.failed("抠图 MCP 工具未配置: " + toolName);
            }
            String raw = tool.call(buildArguments(sourceImageUrl, candidate, instruction));
            return toResult(raw, mcpTimeout());
        } catch (Exception exception) {
            log.warn("Garment cutout MCP call failed, user={}, operation={}, tool={}, type={}",
                    anonymize(externalUserId), operation, toolName,
                    exception.getClass().getSimpleName(), exception);
            return CutoutResult.failed("抠图服务调用失败，请稍后重试");
        }
    }

    private ToolCallback findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        for (ToolCallback callback : toolProvider.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    private String buildArguments(String sourceImageUrl, ClothingCandidate candidate, String instruction) {
        return JSON.createObjectNode()
                .put("sourceImageUrl", sourceImageUrl)
                .put("displayName", safe(candidate.displayName()))
                .put("category", safe(candidate.categoryCode()))
                .put("colorPrimary", safe(candidate.colorPrimary()))
                .put("instruction", safe(instruction))
                .toString();
    }

    private Duration mcpTimeout() {
        Duration configured = properties.getMcp().getTimeout();
        return configured != null ? configured : properties.getProviderTimeout();
    }

    /** 解析 MCP 结果契约；下载失败作为异常传播。 */
    private CutoutResult toResult(String raw, Duration timeout) throws IOException {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (JsonProcessingException exception) {
            return CutoutResult.failed("抠图服务返回格式异常");
        }
        if (node == null || !node.isObject()) {
            return CutoutResult.failed("抠图服务返回格式异常");
        }
        String serverError = node.path("error").asText("");
        if (!serverError.isBlank()) {
            return CutoutResult.failed(serverError.strip());
        }
        // 优先 Base64 内联图片
        String base64 = node.path("imageBase64").asText("");
        if (!base64.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(base64.strip());
                if (bytes.length > 0) {
                    return CutoutResult.image(bytes, node.path("imageUrl").asText(null));
                }
            } catch (IllegalArgumentException exception) {
                return CutoutResult.failed("抠图服务返回格式异常");
            }
        }
        // 其次下载图片 URL
        String imageUrl = node.path("imageUrl").asText("");
        if (!imageUrl.isBlank()) {
            byte[] bytes = downloadImage(imageUrl.strip(), timeout);
            return CutoutResult.image(bytes, imageUrl.strip());
        }
        return CutoutResult.failed("抠图服务返回格式异常");
    }

    private byte[] downloadImage(String value, Duration timeout) throws IOException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid cutout image URL", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("unsupported cutout image URL scheme");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "image/*")
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    IMAGE_DOWNLOAD_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("cutout image URL returned HTTP " + response.statusCode());
            }
            try (InputStream stream = response.body()) {
                return readBytes(stream);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while downloading cutout image", exception);
        }
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("cutout image exceeds maximum size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
```

**设计说明：**

1. **结构完全对齐 `McpVirtualTryOnService`**：同样的 `SyncMcpToolCallbackProvider` 注入、`findTool()` 查找、`toResult()` 解析、`downloadImage()` 下载逻辑
2. **双方法映射**：`cutout()` → `cutoutToolName`，`revise()` → `reviseToolName`（可回退）
3. **结果契约与试衣一致**：JSON 返回 `{ "imageBase64": "...", "imageUrl": "...", "error": "..." }`，优先 Base64，其次下载 URL
4. **入参契约**：`{ "sourceImageUrl": "...", "displayName": "...", "category": "...", "colorPrimary": "...", "instruction": "..." }`
   - 外部 MCP Server 拿到签名 URL 后自行下载源图
   - 候选元数据帮助 Server 理解要提取什么（类别、颜色、名称）
   - `instruction` 传递用户修改指令（如"裤脚保留完整"、"整体窄一点"）

### 3.4 修改 `application-fashion.properties`

路径：`src/main/resources/application-fashion.properties`

在文件末尾（第 87 行 `ragflow.timeout` 之后）追加：

```properties

# ====================================================================
# 服装抠图 provider 切换：reference-image（默认，通用图片编辑）或 mcp（外部抠图 MCP Server）。
# ====================================================================
app.fashion.cutout.provider=${FASHION_CUTOUT_PROVIDER:reference-image}
app.fashion.cutout.provider-timeout=${FASHION_CUTOUT_PROVIDER_TIMEOUT:120s}
# MCP 模式下的工具名（抠图 / 草稿修订；修订工具名留空时回退到抠图工具名）
app.fashion.cutout.mcp.cutout-tool-name=${FASHION_CUTOUT_MCP_CUTOUT_TOOL_NAME:garment_cutout}
app.fashion.cutout.mcp.revise-tool-name=${FASHION_CUTOUT_MCP_REVISE_TOOL_NAME:garment_revise}
# MCP 调用超时（默认复用 provider-timeout，可单独覆盖）
app.fashion.cutout.mcp.timeout=${FASHION_CUTOUT_MCP_TIMEOUT:120s}
# MCP Client 连接外部抠图 MCP Server（SSE 传输）。
# 仅当 provider=mcp 时启用；取消下面两行注释并确保外部抠图服务已启动，
# 否则应用启动时会因连接不到抠图服务而失败。
# spring.ai.mcp.client.sse.connections.cutout-mcp.url=http://localhost:8091
# spring.ai.mcp.client.sse.enabled=true
```

**注意 SSE 连接配置：**
- 抠图 MCP Server 与试衣 MCP Server 可以是同一个服务（共用一个 SSE 连接），也可以是独立服务
- 如果共用，只需保留一组 `spring.ai.mcp.client.sse.connections` 配置，两个 provider 共享 `SyncMcpToolCallbackProvider` 中的工具列表
- 如果独立，需要配置不同的 connection 名称（如 `cutout-mcp` 和 `tryon-mcp`），Spring AI 会自动合并所有连接的工具

### 3.5 新建 `McpGarmentCutoutServiceTest.java`

路径：`src/test/java/com/example/ykdsummer/fashion/application/McpGarmentCutoutServiceTest.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.wardrobe.config.FashionCutoutProperties;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCandidateStatus;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpGarmentCutoutServiceTest {

    private final FashionCutoutProperties properties = new FashionCutoutProperties();
    private final LocalImageAssetStore imageStore = mock(LocalImageAssetStore.class);
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final McpGarmentCutoutService service =
            new McpGarmentCutoutService(provider, imageStore, properties);

    @Test
    void failsWhenTheConfiguredCutoutToolIsNotExposed() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", sourceImage(), candidate(), "");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("抠图 MCP 工具未配置: garment_cutout");
    }

    @Test
    void returnsImageWhenServerReturnsBase64() {
        byte[] imageBytes = {1, 2, 3, 4};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ToolCallback tool = tool("garment_cutout",
                "{\"imageBase64\":\"" + base64 + "\",\"imageUrl\":\"https://cdn.example.com/draft.png\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/source.png");

        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", source, candidate(), "");

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(1, 2, 3, 4);
        assertThat(result.remoteUrl()).isEqualTo("https://cdn.example.com/draft.png");
    }

    @Test
    void surfacesServerError() {
        ToolCallback tool = tool("garment_cutout", "{\"error\":\"source image too small\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/source.png");

        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", source, candidate(), "");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("source image too small");
    }

    @Test
    void failsGracefullyWhenTheCallThrows() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("garment_cutout");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("timeout"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage source = sourceImage();
        when(imageStore.signedReadUrl(source)).thenReturn("https://signed.example.com/source.png");

        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", source, candidate(), "");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("抠图服务调用失败，请稍后重试");
    }

    @Test
    void reviseFallsBackToCutoutToolWhenReviseToolNameIsBlank() {
        properties.getMcp().setReviseToolName("");
        byte[] imageBytes = {5, 6, 7};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ToolCallback tool = tool("garment_cutout", "{\"imageBase64\":\"" + base64 + "\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});
        StoredImage draft = sourceImage();
        when(imageStore.signedReadUrl(draft)).thenReturn("https://signed.example.com/draft.png");

        GarmentCutoutService.CutoutResult result =
                service.revise("user-1", draft, candidate(), "整体窄一点");

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(5, 6, 7);
    }

    @Test
    void reviseUsesDedicatedReviseToolWhenConfigured() {
        byte[] imageBytes = {9, 9, 9};
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        ToolCallback reviseTool = tool("garment_revise", "{\"imageBase64\":\"" + base64 + "\"}");
        ToolCallback cutoutTool = tool("garment_cutout", "{\"error\":\"wrong tool\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{cutoutTool, reviseTool});
        StoredImage draft = sourceImage();
        when(imageStore.signedReadUrl(draft)).thenReturn("https://signed.example.com/draft.png");

        GarmentCutoutService.CutoutResult result =
                service.revise("user-1", draft, candidate(), "改长一点");

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(9, 9, 9);
    }

    @Test
    void returnsFailedWhenSourceImageIsNull() {
        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", null, candidate(), "");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("抠图源图片或候选信息缺失");
    }

    @Test
    void returnsFailedWhenCandidateIsNull() {
        GarmentCutoutService.CutoutResult result =
                service.cutout("user-1", sourceImage(), null, "");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("抠图源图片或候选信息缺失");
    }

    // --- helpers ---

    private static StoredImage sourceImage() {
        return new StoredImage("img_source", 1, Path.of("source.png"), "", null,
                Instant.now(), "image/png", "uploaded", "");
    }

    private static ClothingCandidate candidate() {
        Instant now = Instant.now();
        return new ClothingCandidate("candidate", 1L, "instance", 2L, 0, "白色T恤",
                "T_SHIRT", "WHITE", List.of(), List.of("CASUAL"), "REGULAR", List.of("SUMMER"),
                "{}", new BigDecimal("0.9"), new BigDecimal("0.8"),
                ClothingCompletenessStatus.READY, "", ClothingCandidateStatus.AWAITING_FINAL_CONFIRMATION,
                3L, null, "reference-image", "gpt-4o", "v1",
                now.plusSeconds(600), now, now);
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

### 3.6 新建 `GarmentCutoutProviderSelectionTest.java`

路径：`src/test/java/com/example/ykdsummer/fashion/application/GarmentCutoutProviderSelectionTest.java`

```java
package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.ai.service.AiImageGenerationService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.config.FashionCutoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 验证 provider 切换：同一时刻只有一个 GarmentCutoutService 实现处于激活状态。 */
class GarmentCutoutProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GarmentCutoutProviderContext.class);

    @Test
    void defaultsToReferenceImageProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReferenceImageGarmentCutoutService.class);
            assertThat(context).doesNotHaveBean(McpGarmentCutoutService.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.fashion.cutout.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpGarmentCutoutService.class);
            assertThat(context).doesNotHaveBean(ReferenceImageGarmentCutoutService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ReferenceImageGarmentCutoutService.class, McpGarmentCutoutService.class})
    static class GarmentCutoutProviderContext {
        @Bean
        AiImageGenerationService aiImageGenerationService() { return mock(AiImageGenerationService.class); }

        @Bean
        LocalImageAssetStore localImageAssetStore() { return mock(LocalImageAssetStore.class); }

        @Bean
        SyncMcpToolCallbackProvider toolProvider() { return mock(SyncMcpToolCallbackProvider.class); }

        @Bean
        FashionCutoutProperties fashionCutoutProperties() { return new FashionCutoutProperties(); }
    }
}
```

---

## 四、外部 MCP Server 工具契约

实施本方案前，外部抠图 MCP Server 需暴露以下工具（工具名可配置）：

### 4.1 `garment_cutout` 工具

**入参（JSON）：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sourceImageUrl` | string | 是 | 源图签名 URL，Server 自行下载 |
| `displayName` | string | 否 | 候选显示名（如"白色T恤"），辅助理解 |
| `category` | string | 否 | 标准类目（如 `T_SHIRT`、`JEANS`） |
| `colorPrimary` | string | 否 | 主色（如 `WHITE`） |
| `instruction` | string | 否 | 用户修改指令；为空表示忠实提取 |

**返回（JSON）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `imageBase64` | string | 结果图 Base64（优先） |
| `imageUrl` | string | 结果图 URL（`imageBase64` 为空时使用，Server 需保证可访问） |
| `error` | string | 错误信息；非空时表示失败 |

### 4.2 `garment_revise` 工具（可选）

入参与返回格式与 `garment_cutout` 完全相同。区别在于：
- `sourceImageUrl` 传入的是已有草稿图 URL（而非原始照片）
- 语义上是"基于草稿做局部修改"而非"从原图提取"
- 如果外部 Server 不区分两者，可不实现此工具，配置 `reviseToolName` 留空即可自动回退

---

## 五、与试衣 MCP 的关系

### 5.1 共享 vs 独立 Server

| 方案 | 配置方式 | 优点 | 缺点 |
|------|----------|------|------|
| 共用一个 Server | 一组 SSE 连接，工具列表包含试衣+抠图工具 | 部署简单，少一个进程 | 耦合度高，一个工具崩溃影响另一个 |
| 独立两个 Server | 两组 SSE 连接（`tryon-mcp` + `cutout-mcp`） | 独立扩展和故障隔离 | 多一个进程和端口 |

**建议：** 开发期共用一个 Server（端口 8090），生产环境按需拆分。Spring AI 的 `SyncMcpToolCallbackProvider` 会自动合并所有 SSE 连接的工具，`findTool()` 按工具名查找，不受连接来源影响。

### 5.2 工具名冲突防护

试衣工具名 `virtual_try_on` 和抠图工具名 `garment_cutout` / `garment_revise` 不存在冲突。如果未来共用 Server 暴露了重名工具，`findTool()` 会返回第一个匹配项，因此需保证全局唯一。

---

## 六、改动影响范围

### 6.1 零影响项（完全不变）

- `GarmentCutoutService` 接口 — 无改动
- `FashionWardrobeIngestionService` — 无改动（依赖接口，Spring 注入）
- `FashionGarmentCutoutDispatcher` — 无改动
- `FashionWardrobeIntakeTools` — 无改动（工具层不感知 provider）
- 所有 persistence / domain / runtime 层 — 无改动

### 6.2 行为兼容性

| 场景 | provider=reference-image（默认） | provider=mcp |
|------|----------------------------------|--------------|
| 首次抠图 | 通用图片编辑端点 | 外部 MCP `garment_cutout` |
| 草稿修订 | 通用图片编辑端点 | 外部 MCP `garment_revise`（或回退到 `garment_cutout`） |
| 失败处理 | 返回 `CutoutResult.failed()` | 返回 `CutoutResult.failed()`（格式一致） |
| 结果保存 | `imageStore.saveGenerated()` | `imageStore.saveGenerated()`（完全一致） |
| 完成事件 | `publishCompletion()` | `publishCompletion()`（完全一致） |

**切换 provider 不影响任务持久化、重试、取消、草稿版本管理等任何业务逻辑。** 已有的 PENDING 任务在切换后仍会被正常消费，只是执行路径从旧 provider 切到新 provider。

---

## 七、验证清单

完成实现后逐项自检：

- [ ] `FashionCutoutProperties` 编译通过，`@Component` + `@ConfigurationProperties` 注解正确
- [ ] `ReferenceImageGarmentCutoutService` 添加了 `@ConditionalOnProperty(... matchIfMissing = true)`
- [ ] `McpGarmentCutoutService` 添加了 `@ConditionalOnProperty(... havingValue = "mcp")`
- [ ] 默认配置（不设 `app.fashion.cutout.provider`）下只有 `ReferenceImageGarmentCutoutService` 激活
- [ ] 设置 `app.fashion.cutout.provider=mcp` 后只有 `McpGarmentCutoutService` 激活
- [ ] `application-fashion.properties` 追加了 6 行配置项，环境变量占位符正确
- [ ] `McpGarmentCutoutServiceTest` 8 个测试全部通过
- [ ] `GarmentCutoutProviderSelectionTest` 2 个测试全部通过
- [ ] SSE 连接配置注释行格式与试衣配置一致（默认注释，取消注释才生效）
- [ ] 项目整体 `mvn compile` 通过（如果 Maven 可用）
