# 网页搜索 MCP 替换方案 — 实现任务提示词

## 任务背景

当前项目的网页搜索能力由 `BochaWebSearchTools` 实现，直接调用博查 API。该类同时被 `RealtimeSearchFallback` 依赖（作为其他服务的降级搜索路径）。

目标是通过 Spring AI MCP Client 调用外部通用搜索 MCP Server（如 Brave Search、Tavily、Exa），替代博查 API。要求通过配置开关切换 provider，上层调用方（`RealtimeSearchFallback`）零感知。

## 现有架构关键点（不要改动这些调用方）

- `BochaWebSearchTools`（`com.example.ykdsummer.ai.tool` 包）：`@Component`，`@Tool(name = "search_web")`，构造器注入 `@Value("${bocha.api-key:}")`，核心方法 `searchWeb(String query)` 返回格式化搜索结果字符串
- `RealtimeSearchFallback`（同包）：`@Component`，构造器注入 `BochaWebSearchTools`，调用 `bochaWebSearchTools.searchWeb(query)` 作为降级搜索。判断结果是否成功靠 `result.startsWith("博查搜索结果：")`
- `WebSearchTools`（同包）：`@Component`，`@Tool(name = "web_search")`，依赖 `RealtimeSearchFallback` 做降级。此工具当前未在 `ToolRegistry` 白名单中注册，代码保留但不活跃
- `ToolRegistry` 白名单不包含 `search_web` 和 `web_search`，这两个工具不通过 ToolRegistry 注册给 Fashion Agent
- MCP Client 依赖 `spring-ai-starter-mcp-client` 已在 pom.xml 中引入（试衣替换时添加），无需重复添加
- Spring AI 1.1.8 中 MCP Client 自动配置会注册 `SyncMcpToolCallbackProvider` Bean

## 需要完成的改动

### 1. 新增 WebSearchProvider 接口

路径：`com.example.ykdsummer.ai.tool.WebSearchProvider`

```java
package com.example.ykdsummer.ai.tool;

/** Replaceable web search port so the fallback path is agnostic to the underlying provider. */
public interface WebSearchProvider {
    /** 执行网页搜索，返回格式化结果字符串。成功时以 "搜索结果：" 开头，失败时返回错误说明。 */
    String search(String query);
}
```

### 2. BochaWebSearchTools — 实现接口 + 加条件注解

改动现有类：
- 实现 `WebSearchProvider` 接口，新增 `@Override public String search(String query)` 方法，内部直接调用已有的 `searchWeb(query)`
- 加 `@ConditionalOnProperty(prefix = "app.web-search", name = "provider", havingValue = "bocha", matchIfMissing = true)`
- 保持 `@Component` 和 `@Tool(name = "search_web")` 注解不变
- 保持现有 `searchWeb` 方法签名和全部逻辑不变
- `search` 方法体：`return searchWeb(query);`

### 3. 新增 McpWebSearchTools — MCP 实现

路径：`com.example.ykdsummer.ai.tool.McpWebSearchTools`

职责：实现 `WebSearchProvider`，通过 Spring AI MCP Client 调用外部搜索 MCP Server 的工具。同时标注 `@Tool(name = "search_web")` 以保持工具名一致。

设计要点：
- 标注 `@Component` + `@ConditionalOnProperty(prefix = "app.web-search", name = "provider", havingValue = "mcp")`
- 注入 `SyncMcpToolCallbackProvider`（从 MCP Client 自动配置获得）
- 注入 `WebSearchProperties`（新增配置类，见下方第 4 点）

`search` 方法逻辑：
1. 从 `SyncMcpToolCallbackProvider.getToolCallbacks()` 中按 `properties.toolName` 找到目标工具
2. 工具未找到时返回 `"搜索服务未配置: " + toolName`
3. 构造参数 JSON：`{"query": "用户查询词"}`
4. 调用 `tool.call(jsonArgs)`，返回值是字符串
5. 解析返回值：MCP Server 应返回 JSON，包含 `results` 数组，每项有 `title`、`url`、`snippet`
6. 格式化为与博查一致的结果字符串：`"搜索结果：\n1. 标题\n来源：URL\n摘要：snippet\n\n..."`
7. 无结果时返回 `"搜索没有可用结果"`
8. 异常时返回 `"搜索失败：请稍后重试"`

同时定义 `@Tool` 方法：
```java
@Tool(name = "search_web", description = "搜索公开网页中的实时信息、新闻和热点。"
        + "只有问题需要最新外部事实时才调用；返回来源链接和摘要。")
public String searchWeb(@ToolParam(required = true, description = "要搜索的关键词或完整问题") String query) {
    return search(query);
}
```

异常处理：
- 用 SLF4J 记录 warn 日志
- 不抛异常到外部，所有失败路径返回错误字符串

JSON 解析用 Jackson `ObjectMapper`（项目已有依赖）。

### 4. 新增 WebSearchProperties 配置类

路径：`com.example.ykdsummer.ai.tool.WebSearchProperties`

```java
package com.example.ykdsummer.ai.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.web-search")
public class WebSearchProperties {
    /** 搜索 provider：{@code bocha}（默认）或 {@code mcp}。 */
    private String provider = "bocha";
    /** MCP 模式下调用的工具名。 */
    private String toolName = "web_search";
    /** MCP 搜索结果最大条数。 */
    private int maxResults = 8;

    // getter/setter 省略，需要完整实现
}
```

注意：此项目约定配置类不能同时使用 `@Component` 和 `@ConfigurationProperties`（会导致重复 Bean）。但现有代码中 `FashionTryOnProperties` 已用此模式且未修正，为保持一致性，此处也用 `@Component + @ConfigurationProperties`。如果项目主类已有 `@EnableConfigurationProperties(WebSearchProperties.class)` 则改为纯 `@ConfigurationProperties` 不加 `@Component`。实现时检查主类 `WeChatRobotDemo.java` 的 `@EnableConfigurationProperties` 注解，按已有约定处理。

### 5. RealtimeSearchFallback — 改依赖为接口

改动现有类：
- 构造器参数从 `BochaWebSearchTools` 改为 `ObjectProvider<WebSearchProvider>`（因为两个实现互斥，只有一个会存在）
- `search` 方法中调用 `provider.getIfAvailable()` 获取实例
- 如果实例为 null，返回 `capability + "暂时不可用：" + reason + "，且搜索服务未配置。"`
- 调用 `webSearchProvider.search(query)` 替代 `bochaWebSearchTools.searchWeb(query)`
- 判断成功标志从 `result.startsWith("博查搜索结果：")` 改为 `result.startsWith("搜索结果：")`（两个 provider 都用这个前缀）
- 保留 `unavailable()` 静态方法，改为返回 `new RealtimeSearchFallback(null)`

### 6. application.properties — 新增配置

在 `application.properties` 中新增：
```properties
# 网页搜索 provider 切换：bocha（默认）或 mcp
app.web-search.provider=${WEB_SEARCH_PROVIDER:bocha}
# MCP 模式下的工具名
app.web-search.tool-name=${WEB_SEARCH_MCP_TOOL_NAME:web_search}
app.web-search.max-results=${WEB_SEARCH_MAX_RESULTS:8}
```

在 `application-local.template.properties` 中新增注释说明：
```properties
# 网页搜索 provider：bocha（默认）或 mcp
# mcp 模式需取消注释 MCP Client SSE 连接配置并确保外部搜索服务已启动
app.web-search.provider=bocha
```

### 7. MCP Client SSE 连接配置

在 `application.properties` 中以注释形式新增（与试衣 MCP 相同的处理方式，默认注释避免破坏启动）：
```properties
# MCP Client 连接外部搜索 MCP Server（SSE 传输）
# 仅当 app.web-search.provider=mcp 时取消注释并确保服务已启动
# spring.ai.mcp.client.sse.connections.search-mcp.url=http://localhost:8091
```

注意：如果试衣 MCP 的 SSE 连接也已注释，多个 MCP 连接可以共存于同一 `spring.ai.mcp.client.sse.connections` map 下，key 不同即可。

### 8. 外部 MCP Server 的工具契约（供部署参考）

外部搜索 MCP Server 需暴露名为 `web_search` 的工具：
```
工具名: web_search
描述: 搜索公开网页，返回实时信息

输入参数 (JSON):
  - query: string (搜索关键词或问题)

返回值 (JSON string):
  成功: {"results": [{"title": "...", "url": "...", "snippet": "..."}, ...]}
  失败: {"error": "失败原因"}
  无结果: {"results": []}
```

## 验证要求

1. 不配置 `app.web-search.provider` 时，`BochaWebSearchTools` 生效，行为与改动前完全一致
2. 配置 `app.web-search.provider=mcp` 时，`McpWebSearchTools` 生效，`BochaWebSearchTools` 不注册
3. 两个实现不会同时存在于 Spring 容器
4. `RealtimeSearchFallback` 能正确工作于两种 provider（通过 `WebSearchProvider` 接口多态）
5. `WebSearchTools`（`web_search` 工具）的降级路径不受影响——它通过 `RealtimeSearchFallback` 调用，不直接依赖具体实现
6. 编译通过（Maven），无未使用 import 警告
7. 新增单元测试：`McpWebSearchToolsTest`（工具未配置/正常返回/空结果/error/异常 5 个用例）

## 不要做的事

- 不要改动 `WebSearchTools`（`web_search` 工具）的任何逻辑，它只是 `RealtimeSearchFallback` 的消费方
- 不要改动 `ToolRegistry` 白名单
- 不要删除 `BochaWebSearchTools`，只加条件注解
- 不要引入新的 HTTP 客户端库，MCP 工具调用用 Spring AI 的 `ToolCallback` API
- 不要在 `McpWebSearchTools` 中依赖 `BochaWebSearchTools` 或 `RestClient`
- 不要给配置类同时加 `@Component` 和 `@EnableConfigurationProperties`（按项目现有约定处理）
