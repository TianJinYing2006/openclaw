# 虚拟试衣 MCP 替换方案 — 实现任务提示词

## 任务背景

当前项目的虚拟试衣功能由 `ReferenceImageVirtualTryOnService` 实现，本质是调用 OpenAI 兼容的通用图片编辑端点，靠长提示词指导模型"把衣服穿到人身上"。这种方式衣物定位不精确、布料质感容易丢失、失败率较高。

目标是新增一个 `McpVirtualTryOnService`，通过 Spring AI MCP Client 调用外部专用试衣 MCP Server（如基于 IDM-VTON / Kolors / OOTDiffusion 封装的服务），替代当前的通用图片编辑实现。要求通过配置开关切换 provider，上层调度/持久化/回图管道完全不动。

## 现有架构关键点（不要改动这些）

- `VirtualTryOnService` 是可替换的 provider 端口接口，只有一个方法：
  ```java
  TryOnResult render(String externalUserId, StoredImage personImage, StoredImage garmentImage,
                     String garmentCategoryCode, Duration timeout);
  ```
- `FashionVirtualTryOnService.execute()` 调用 `renderer.render()`，上层调度、持久化、崩溃恢复、事件发布、微信回图全部基于此，不感知底层 provider。
- `ReferenceImageVirtualTryOnService` 是当前唯一实现，标注 `@Service`，无条件注册。
- 图片通过 `LocalImageAssetStore.signedReadUrl(StoredImage)` 获取短时可访问 URL。
- `FashionTryOnProperties`（`app.fashion.tryon` 前缀）目前只有 `providerTimeout` 一个配置项。
- 项目 Spring AI 版本为 1.1.8，Spring Boot 3.5.16，Java 21。

## 需要完成的改动

### 1. pom.xml — 新增 MCP Client 依赖

在 `<dependencies>` 中新增（版本由 spring-ai-bom 管理，不要写死版本号）：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

### 2. FashionTryOnProperties — 新增 provider 配置项

在 `app.fashion.tryon` 前缀下新增：
- `provider`：字符串，可选值 `reference-image`（默认，当前实现）或 `mcp`（新实现）
- `mcp.tool-name`：字符串，外部 MCP Server 暴露的试衣工具名，默认 `virtual_try_on`
- `mcp.timeout`：Duration，MCP 调用超时，默认复用 `providerTimeout`

注意：此项目约定配置类不能同时使用 `@Component` 和 `@ConfigurationProperties`，统一通过主类的 `@EnableConfigurationProperties` 注册。当前 `FashionTryOnProperties` 已带 `@Component`，新增字段时不要改变其注册方式，仅新增字段和 getter/setter。

### 3. ReferenceImageVirtualTryOnService — 加条件注解

给现有实现加上条件注解，只在 `provider=reference-image`（或未配置）时生效：
```java
@Service
@ConditionalOnProperty(prefix = "app.fashion.tryon", name = "provider",
        havingValue = "reference-image", matchIfMissing = true)
public class ReferenceImageVirtualTryOnService implements VirtualTryOnService { ... }
```

### 4. 新增 McpVirtualTryOnService — 核心实现

路径：`com.example.ykdsummer.fashion.wardrobe.application.McpVirtualTryOnService`

职责：实现 `VirtualTryOnService`，通过 Spring AI MCP Client 调用外部试衣 MCP Server 的工具。

设计要点：
- 标注 `@Service` + `@ConditionalOnProperty(prefix = "app.fashion.tryon", name = "provider", havingValue = "mcp")`
- 注入 `List<ToolCallback>`（Spring AI MCP Client 自动配置会把远程 MCP 工具注册为 ToolCallback Bean）或 `SyncMcpToolCallbackProvider`
- 注入 `LocalImageAssetStore`（复用 signedReadUrl 机制生成人物图和衣物图的短时 URL）
- 注入 `FashionTryOnProperties`（读取 mcp.tool-name 和超时）

`render` 方法逻辑：
1. 空值检查：personImage / garmentImage 为 null 时返回 `TryOnResult.failed("试衣素材图片不可用")`
2. 生成两张图的 signedReadUrl
3. 从注入的 ToolCallback 列表中按 `properties.mcp.toolName` 找到目标工具
4. 构造工具参数 JSON，包含：
   - `personImageUrl`：人物模板图 URL
   - `garmentImageUrl`：衣物图 URL
   - `garmentCategory`：衣物类目码（如 OUTER、T_SHIRT、JEANS）
5. 调用 `toolCallback.call(jsonArgs)`，返回值是字符串
6. 解析返回值：MCP Server 应返回 JSON，包含 `imageUrl`（结果图 URL）或 `imageBase64`（Base64 编码）
7. 下载结果图字节（可参考 `AiImageGenerationService.downloadImage` 的 HTTP 下载逻辑，但不要直接依赖该类，自行用 java.net.http.HttpClient 实现）
8. 返回 `TryOnResult.image(bytes, remoteUrl)`；失败时返回 `TryOnResult.failed(errorMessage)`

异常处理：
- 工具未找到：返回 `TryOnResult.failed("试衣 MCP 工具未配置: " + toolName)`
- 调用超时/异常：返回 `TryOnResult.failed("试衣服务调用失败，请稍后重试")`
- 结果解析失败：返回 `TryOnResult.failed("试衣服务返回格式异常")`
- 用 SLF4J 记录 warn 日志，userId 脱敏（参考现有 `anonymize` 风格）

### 5. application-fashion.properties — 新增配置示例

```properties
# 虚拟试衣 provider 切换：reference-image（默认）或 mcp
app.fashion.tryon.provider=reference-image
# MCP 模式下的工具名
app.fashion.tryon.mcp.tool-name=virtual_try_on
# MCP 调用超时（默认复用 provider-timeout，可单独覆盖）
app.fashion.tryon.mcp.timeout=150s

# MCP Client 连接外部试衣 MCP Server（SSE 传输）
spring.ai.mcp.client.sse.connections.tryon-mcp.url=http://localhost:8090
spring.ai.mcp.client.sse.enabled=true
```

### 6. 外部 MCP Server 的工具契约（供部署参考）

外部试衣 MCP Server 需暴露名为 `virtual_try_on` 的工具，参数和返回如下：
```
工具名: virtual_try_on
描述: 接收人物图和衣物图 URL，生成虚拟试穿效果图

输入参数 (JSON):
  - personImageUrl: string  (人物全身模板图 URL)
  - garmentImageUrl: string (衣物展示图 URL)
  - garmentCategory: string (衣物类目，如 OUTER/T_SHIRT/JEANS)

返回值 (JSON string):
  成功: {"imageUrl": "https://...结果图URL", "imageBase64": "...可选"}
  失败: {"error": "失败原因描述"}
```

## 验证要求

1. 不配置 `app.fashion.tryon.provider` 时，`ReferenceImageVirtualTryOnService` 生效，行为与改动前完全一致
2. 配置 `app.fashion.tryon.provider=mcp` 时，`McpVirtualTryOnService` 生效，`ReferenceImageVirtualTryOnService` 不注册
3. 两个实现不会同时存在于 Spring 容器（`FashionVirtualTryOnService` 注入 `VirtualTryOnService` 时只能匹配到一个）
4. MCP 调用失败时返回 `TryOnResult.failed`，上层 `FashionVirtualTryOnService.execute()` 会将任务标记为 FAILED，不会抛异常中断调度器
5. 编译通过（Maven），无未使用 import 警告

## 不要做的事

- 不要改动 `FashionVirtualTryOnService`、`FashionTryOnDispatcher`、`FashionTryOnTools`、`JdbcFashionTryOnRepository`、`ILinkFashionTryOnCompletionListener` 等上层文件
- 不要改动 `VirtualTryOnService` 接口签名
- 不要引入新的 HTTP 客户端库（如 OkHttp、RestTemplate），MCP 工具调用用 Spring AI 提供的 API，图片下载用 java.net.http.HttpClient
- 不要在 `McpVirtualTryOnService` 中依赖 `AiImageGenerationService`（那是被替代的旧路径）
- 不要给配置类同时加 `@Component` 和 `@ConfigurationProperties`
