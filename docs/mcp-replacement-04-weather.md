# MCP 替换方案 04：天气服务（Weather Service）

> 本方案供 Trae 直接实现。所有路径均为项目相对路径，改动完成后请按文末验证清单自检。

---

## 一、现状分析

### 1.1 天气服务工作流

```
用户问天气 → 模型调用 get_current_weather 工具
           → WeatherTools.getCurrentWeather(city)
           → WeatherService.getCurrentWeather(city)   ← 替换点
               ├─ normalizeCity()：参数校验
               └─ RestClient GET uapis.cn/api/v1/misc/weather?city=xxx&lang=zh
           → WeatherApiResponse → WeatherInfo（稳定领域记录）
           → 返回给模型生成自然语言回复
```

### 1.2 现有实现

文件：`src/main/java/com/example/ykdsummer/weather/WeatherService.java`

- `@Service` 无条件注册
- 调用 `uapis.cn` 公共天气 API（免费、无需 API Key）
- `normalizeCity()` — 参数校验（非空、≤50 字符）
- `WeatherApiResponse` — 私有 record，映射第三方 JSON 字段（含 `@JsonProperty` 蛇形转驼峰）
- 返回 `WeatherInfo` — 稳定领域记录，不暴露第三方原始 JSON

文件：`src/main/java/com/example/ykdsummer/weather/WeatherInfo.java`

- 公共 record，8 个字段：`province`、`city`、`weather`、`temperatureCelsius`、`windDirection`、`windPower`、`humidityPercent`、`reportTime`

### 1.3 调用方

**直接调用方：**

| 文件 | 引用方式 | 说明 |
|------|----------|------|
| `ai/tool/WeatherTools.java` | `WeatherService` 字段 + 构造注入 | AI 工具边界，调用 `getCurrentWeather(city)` |
| `ai/service/AiTraceLogger.java` | `import WeatherInfo` | 日志记录工具结果（泛型 `toolResult(String, Object)`，不强依赖） |

**间接关联（不调用 `WeatherService`，但使用天气数据）：**

- `fashion/tool/FashionOutfitRecommendationTools.java` — `weatherSummary` 是 String 参数，由模型从天气工具结果中提取后传入，不直接依赖 `WeatherService`
- `fashion/application/OutfitRecommendationEngine.java` — `inferSeasons(weatherSummary)` 解析温度数字推断季节，不依赖 `WeatherService`

**`WeatherService` 的唯一直接调用方是 `WeatherTools`。**

### 1.4 核心问题：无 SPI 接口

`WeatherService` 是具体 `@Service` 类，不是接口。与方案 03 类似，需要先提取 SPI 接口。

但与方案 03 不同的是，`WeatherService` 的调用方只有 `WeatherTools` 一个，且 `WeatherTools` 已经是工具边界层。因此有两种替换策略：

**策略 A（接口提取 + 条件 Bean）：** 与前三个方案一致，提取 `WeatherProvider` 接口，`WeatherService` 和 `McpWeatherProvider` 都实现它。
- 优点：模式统一，`WeatherTools` 依赖接口
- 缺点：多一个接口文件

**策略 B（直接在 `WeatherTools` 层切换）：** 不提取接口，给 `WeatherService` 加条件注解，新建 `McpWeatherService` 替代它，`WeatherTools` 不变（Spring 自动注入当前实现）。
- 优点：改动更少
- 缺点：`WeatherTools` 依赖具体类 `WeatherService`，切换时需要两个类都继承同一接口或 `WeatherTools` 改为依赖 `Object`

**本方案选择策略 A**，与前三个方案保持一致，且接口提取后 `WeatherTools` 依赖接口更符合依赖倒置原则。

### 1.5 测试文件

| 测试文件 | 测试目标 | 影响 |
|----------|----------|------|
| `weather/WeatherServiceTest.java` | `WeatherService` + MockRestServiceServer | 无改动（直接构造 `WeatherService`） |
| `ai/tool/WeatherToolsTest.java` | `WeatherTools` + mock `WeatherService` | 无改动（mock 的是具体类，接口提取后仍可 mock） |

---

## 二、改动清单

| 序号 | 文件 | 操作 | 说明 |
|------|------|------|------|
| 1 | `weather/WeatherProvider.java` | 新建 | SPI 接口 |
| 2 | `weather/WeatherService.java` | 修改 | 实现接口 + 加条件注解 |
| 3 | `weather/McpWeatherProvider.java` | 新建 | MCP 实现，调用外部天气工具 |
| 4 | `weather/WeatherProperties.java` | 新建 | 天气 provider 切换配置 |
| 5 | `ai/tool/WeatherTools.java` | 修改 | 依赖接口替代具体类 |
| 6 | `application.properties` | 修改 | 追加天气 provider 配置项 |
| 7 | `McpWeatherProviderTest.java` | 新建 | MCP 实现单元测试 |
| 8 | `WeatherProviderSelectionTest.java` | 新建 | provider 切换条件测试 |

---

## 三、详细实现

### 3.1 新建 `WeatherProvider.java`

路径：`src/main/java/com/example/ykdsummer/weather/WeatherProvider.java`

```java
package com.example.ykdsummer.weather;

/** Replaceable provider boundary for querying current weather by city name. */
public interface WeatherProvider {

    /** Query current weather for the given city. Throws on invalid input or service failure. */
    WeatherInfo getCurrentWeather(String city);
}
```

### 3.2 修改 `WeatherService.java`

路径：`src/main/java/com/example/ykdsummer/weather/WeatherService.java`

**改动 1：** 添加 `implements WeatherProvider` 和 `@ConditionalOnProperty`

改动前（第 11-12 行）：
```java
@Service
public class WeatherService {
```

改动后：
```java
@Service
@ConditionalOnProperty(prefix = "app.weather", name = "provider",
        havingValue = "uapis", matchIfMissing = true)
public class WeatherService implements WeatherProvider {
```

添加 import：
```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

**改动 2：** `getCurrentWeather` 方法添加 `@Override`

改动前（第 22 行）：
```java
public WeatherInfo getCurrentWeather(String city) {
```

改动后：
```java
@Override
public WeatherInfo getCurrentWeather(String city) {
```

**方法体完全不变。**

### 3.3 新建 `WeatherProperties.java`

路径：`src/main/java/com/example/ykdsummer/weather/WeatherProperties.java`

```java
package com.example.ykdsummer.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 天气 provider 切换配置。 */
@Component
@ConfigurationProperties(prefix = "app.weather")
public class WeatherProperties {
    /**
     * 天气 provider：
     * {@code uapis}（默认，uapis.cn 公共 API）或 {@code mcp}（外部天气 MCP Server）。
     */
    private String provider = "uapis";
    private final Mcp mcp = new Mcp();

    public String getProvider() { return provider; }
    public void setProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            this.provider = provider;
        }
    }

    public Mcp getMcp() { return mcp; }

    /** MCP 模式详情，绑定 {@code app.weather.mcp.*}。 */
    public static class Mcp {
        /** 外部天气 MCP Server 提供的工具名。 */
        private String toolName = "get_weather";

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.toolName = toolName;
            }
        }
    }
}
```

### 3.4 新建 `McpWeatherProvider.java`

路径：`src/main/java/com/example/ykdsummer/weather/McpWeatherProvider.java`

```java
package com.example.ykdsummer.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部天气 MCP Server 工具替代 uapis.cn 公共 API。
 *
 * <p>外部 Server 接收城市名，返回结构化天气 JSON。本实现负责解析响应并映射到
 * {@link WeatherInfo}，保持与 {@link WeatherService} 相同的领域模型。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.weather", name = "provider", havingValue = "mcp")
public class McpWeatherProvider implements WeatherProvider {
    private static final Logger log = LoggerFactory.getLogger(McpWeatherProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SyncMcpToolCallbackProvider toolProvider;
    private final WeatherProperties properties;

    public McpWeatherProvider(SyncMcpToolCallbackProvider toolProvider, WeatherProperties properties) {
        this.toolProvider = toolProvider;
        this.properties = properties;
    }

    @Override
    public WeatherInfo getCurrentWeather(String city) {
        String normalizedCity = normalizeCity(city);
        String toolName = properties.getMcp().getToolName();
        try {
            ToolCallback tool = findTool(toolName);
            if (tool == null) {
                log.warn("Weather MCP tool not found, tool={}", toolName);
                throw new IllegalStateException("天气服务未配置: " + toolName);
            }
            String jsonArgs = JSON.createObjectNode()
                    .put("city", normalizedCity)
                    .toString();
            String raw = tool.call(jsonArgs);
            return parse(raw);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Weather MCP call failed, tool={}, type={}", toolName,
                    exception.getClass().getSimpleName(), exception);
            throw new IllegalStateException("天气服务调用失败，请稍后重试");
        }
    }

    private ToolCallback findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        for (ToolCallback callback : toolProvider.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        return null;
    }

    /** Parses the MCP result JSON into the stable WeatherInfo domain record. */
    WeatherInfo parse(String raw) {
        JsonNode node;
        try {
            node = JSON.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException("天气服务返回格式异常");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("天气服务返回格式异常");
        }
        String cityName = text(node.path("city"), 64);
        if (cityName.isBlank()) {
            throw new IllegalStateException("天气服务没有返回有效数据");
        }
        return new WeatherInfo(
                text(node.path("province"), 64),
                cityName,
                text(node.path("weather"), 64),
                intValue(node.path("temperature")),
                text(node.path("windDirection"), 32),
                text(node.path("windPower"), 32),
                intValue(node.path("humidity")),
                text(node.path("reportTime"), 128)
        );
    }

    private static String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
        String normalized = city.strip();
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("城市名称过长");
        }
        return normalized;
    }

    private static String text(JsonNode node, int limit) {
        if (node == null || node.isNull() || !node.isTextual()) return "";
        String value = node.asText("").replace('\u0000', ' ').strip();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static Integer intValue(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) return null;
        return node.asInt();
    }
}
```

**设计说明：**

1. **`normalizeCity()` 有意重复**：与 `WeatherService` 中的实现一致。参数校验是 provider 无关的业务规则，每个实现都应执行。不提取为共享方法是因为：
   - 方法体极短（6 行），提取后的间接调用反而增加复杂度
   - 两个实现可能独立演化（MCP Server 可能接受城市编码而非名称）
   - 与方案 03 中 `category()` 等方法的重复策略一致

2. **`parse()` 映射到 `WeatherInfo`**：外部 MCP Server 返回的 JSON 字段名使用驼峰命名（`windDirection` 而非 `wind_direction`），与 `WeatherInfo` 的字段名一致。这简化了解析逻辑，无需 `@JsonProperty` 转换。

3. **异常类型对齐**：`normalizeCity()` 抛 `IllegalArgumentException`（参数错误），服务调用失败抛 `IllegalStateException`，与 `WeatherService` 的异常模式一致。`WeatherTools` 捕获 `RuntimeException` 并向上传播，不区分具体类型。

4. **`Integer` 可空字段**：`temperature` 和 `humidity` 在 `WeatherInfo` 中是 `Integer`（可为 null），`parse()` 中用 `canConvertToInt()` 安全处理缺失或非数字字段。

### 3.5 修改 `WeatherTools.java`

路径：`src/main/java/com/example/ykdsummer/ai/tool/WeatherTools.java`

**改动 1：** import 和字段类型改为接口

改动前：
```java
import com.example.ykdsummer.weather.WeatherInfo;
import com.example.ykdsummer.weather.WeatherService;
// ...
private final WeatherService weatherService;
```

改动后：
```java
import com.example.ykdsummer.weather.WeatherInfo;
import com.example.ykdsummer.weather.WeatherProvider;
// ...
private final WeatherProvider weatherService;
```

**改动 2：** 构造函数参数类型改为接口

改动前（第 20-21 行）：
```java
public WeatherTools(WeatherService weatherService) {
    this(weatherService, AiTraceLogger.disabled());
}

@Autowired
public WeatherTools(WeatherService weatherService, AiTraceLogger trace) {
```

改动后：
```java
public WeatherTools(WeatherProvider weatherService) {
    this(weatherService, AiTraceLogger.disabled());
}

@Autowired
public WeatherTools(WeatherProvider weatherService, AiTraceLogger trace) {
```

**方法体完全不变**——`weatherService.getCurrentWeather(city)` 调用对接口和具体类都有效。

### 3.6 修改 `application.properties`

路径：`src/main/resources/application.properties`

在 `uapis.api-key` 配置附近追加：

```properties

# 天气 provider 切换：uapis（默认，uapis.cn 公共 API）或 mcp（外部天气 MCP Server）。
app.weather.provider=${WEATHER_PROVIDER:uapis}
# MCP 模式下的工具名
app.weather.mcp.tool-name=${WEATHER_MCP_TOOL_NAME:get_weather}
# MCP Client 连接外部天气 MCP Server（SSE 传输）。
# 仅当 provider=mcp 时启用；取消下面两行注释并确保外部天气服务已启动。
# 注意：如果穿搭相关功能共用同一个 MCP Server，只需保留一组 SSE 连接即可。
# spring.ai.mcp.client.sse.connections.weather-mcp.url=http://localhost:8093
# spring.ai.mcp.client.sse.enabled=true
```

### 3.7 新建 `McpWeatherProviderTest.java`

路径：`src/test/java/com/example/ykdsummer/weather/McpWeatherProviderTest.java`

```java
package com.example.ykdsummer.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpWeatherProviderTest {

    private final WeatherProperties properties = new WeatherProperties();
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final McpWeatherProvider service = new McpWeatherProvider(provider, properties);

    @Test
    void parsesStructuredWeatherJsonIntoWeatherInfo() {
        ToolCallback tool = tool("get_weather", """
                {"province":"浙江省","city":"杭州市","weather":"小雨",
                 "temperature":26,"windDirection":"西南风","windPower":"2级",
                 "humidity":95,"reportTime":"5 分钟前发布"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("杭州");

        assertThat(result).isEqualTo(new WeatherInfo(
                "浙江省", "杭州市", "小雨", 26,
                "西南风", "2级", 95, "5 分钟前发布"));
    }

    @Test
    void normalizesCityBeforeCallingTheTool() {
        ToolCallback tool = tool("get_weather", """
                {"province":"湖北省","city":"武汉市","weather":"晴",
                 "temperature":32,"windDirection":"东南风","windPower":"3级",
                 "humidity":60,"reportTime":"刚刚发布"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("  武汉  ");

        assertThat(result.city()).isEqualTo("武汉市");
    }

    @Test
    void rejectsBlankCityBeforeCallingTheTool() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getCurrentWeather("  "))
                .withMessage("城市名称不能为空");
    }

    @Test
    void rejectsOverlyLongCityName() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getCurrentWeather("a".repeat(51)))
                .withMessage("城市名称过长");
    }

    @Test
    void throwsWhenTheConfiguredToolIsNotExposed() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务未配置");
    }

    @Test
    void throwsWhenTheServerReturnsAnError() {
        ToolCallback tool = tool("get_weather", "{\"error\":\"city not found\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("未知城市"))
                .withMessageContaining("天气服务没有返回有效数据");
    }

    @Test
    void throwsWhenTheResponseIsMalformed() {
        ToolCallback tool = tool("get_weather", "not-json");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务返回格式异常");
    }

    @Test
    void throwsWhenTheCallFails() {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("get_weather");
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenThrow(new RuntimeException("timeout"));
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        assertThatIllegalStateException()
                .isThrownBy(() -> service.getCurrentWeather("杭州"))
                .withMessageContaining("天气服务调用失败");
    }

    @Test
    void toleratesMissingOptionalFields() {
        ToolCallback tool = tool("get_weather", """
                {"city":"深圳市","weather":"多云"}""");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        WeatherInfo result = service.getCurrentWeather("深圳");

        assertThat(result.city()).isEqualTo("深圳市");
        assertThat(result.weather()).isEqualTo("多云");
        assertThat(result.province()).isEmpty();
        assertThat(result.temperatureCelsius()).isNull();
        assertThat(result.humidityPercent()).isNull();
        assertThat(result.windDirection()).isEmpty();
    }

    // --- helpers ---

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

**设计说明：**

- `toleratesMissingOptionalFields` 测试验证 `temperature` 和 `humidity` 为 null 时的容错（`WeatherInfo` 中这两个字段是 `Integer` 可空）
- `throwsWhenTheServerReturnsAnError` 测试验证 Server 返回 `{"error":"..."}` 但没有 `city` 字段时，`parse()` 走 `cityName.isBlank()` 分支抛异常

### 3.8 新建 `WeatherProviderSelectionTest.java`

路径：`src/test/java/com/example/ykdsummer/weather/WeatherProviderSelectionTest.java`

```java
package com.example.ykdsummer.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/** 验证 provider 切换：同一时刻只有一个 WeatherProvider 实现处于激活状态。 */
class WeatherProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WeatherProviderContext.class);

    @Test
    void defaultsToUapisProviderWhenUnconfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WeatherService.class);
            assertThat(context).doesNotHaveBean(McpWeatherProvider.class);
        });
    }

    @Test
    void switchesToMcpProviderWhenConfigured() {
        runner.withPropertyValues("app.weather.provider=mcp").run(context -> {
            assertThat(context).hasSingleBean(McpWeatherProvider.class);
            assertThat(context).doesNotHaveBean(WeatherService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({WeatherService.class, McpWeatherProvider.class})
    static class WeatherProviderContext {
        @Bean
        RestClient.Builder restClientBuilder() { return RestClient.builder(); }

        @Bean
        SyncMcpToolCallbackProvider toolProvider() { return mock(SyncMcpToolCallbackProvider.class); }

        @Bean
        WeatherProperties weatherProperties() { return new WeatherProperties(); }
    }
}
```

---

## 四、外部 MCP Server 工具契约

实施本方案前，外部天气 MCP Server 需暴露以下工具（工具名可配置）：

### 4.1 `get_weather` 工具

**入参（JSON）：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `city` | string | 是 | 城市名称（已规范化，去除前后空格，≤50 字符） |

**返回（JSON）：**

```json
{
  "province": "浙江省",
  "city": "杭州市",
  "weather": "小雨",
  "temperature": 26,
  "windDirection": "西南风",
  "windPower": "2级",
  "humidity": 95,
  "reportTime": "5 分钟前发布"
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `province` | string | 否 | 省份（可空） |
| `city` | string | 是 | 城市名（为空时视为无效数据） |
| `weather` | string | 否 | 天气描述（如"小雨"、"晴"） |
| `temperature` | number | 否 | 温度（摄氏度，可空） |
| `windDirection` | string | 否 | 风向（如"西南风"） |
| `windPower` | string | 否 | 风力（如"2级"） |
| `humidity` | number | 否 | 湿度百分比（可空） |
| `reportTime` | string | 否 | 发布时间描述 |

**字段命名约定：** 驼峰命名（`windDirection` 而非 `wind_direction`），与 `WeatherInfo` record 字段名一致。

---

## 五、与其他 MCP 的关系

### 5.1 四组 MCP 工具共存

| 功能 | provider 配置前缀 | 默认工具名 | 可共用 Server |
|------|-------------------|-----------|--------------|
| 虚拟试衣 | `app.fashion.tryon` | `virtual_try_on` | 是 |
| 服装抠图 | `app.fashion.cutout` | `garment_cutout` / `garment_revise` | 是 |
| 服装识别 | `app.fashion.analysis` | `wardrobe_photo_analysis` | 是 |
| 天气服务 | `app.weather` | `get_weather` | 是 |

天气服务与穿搭功能逻辑上关联（天气影响搭配推荐），但物理上可以共用或独立部署。

### 5.2 四个 provider 独立切换

```properties
app.fashion.tryon.provider=mcp          # 试衣走 MCP
app.fashion.cutout.provider=mcp         # 抠图走 MCP
app.fashion.analysis.provider=mcp       # 识别走 MCP
app.weather.provider=mcp                # 天气走 MCP
```

也可以混合使用：

```properties
app.fashion.tryon.provider=mcp                    # 试衣走 MCP
app.fashion.cutout.provider=reference-image       # 抠图仍走通用图片编辑
app.fashion.analysis.provider=chat-completions    # 识别仍走 ChatCompletions
app.weather.provider=uapis                        # 天气仍走 uapis.cn
```

---

## 六、改动影响范围

### 6.1 零影响项（完全不变）

- `WeatherInfo` — 无改动（稳定领域记录）
- `WeatherTools` 方法体 — 无改动（`weatherService.getCurrentWeather(city)` 调用不变）
- `AiTraceLogger` — 无改动（泛型 `toolResult(String, Object)`，import `WeatherInfo` 不变）
- `OutfitRecommendationEngine` — 无改动（不依赖 `WeatherService`，只接收 `weatherSummary` String）
- `FashionOutfitRecommendationTools` — 无改动（同上）
- `ToolRegistry` — 无改动（白名单中的 `get_current_weather` 工具名不变）
- 所有 persistence / domain / runtime 层 — 无改动

### 6.2 测试兼容性

| 测试文件 | 影响 | 说明 |
|----------|------|------|
| `WeatherServiceTest` | 无改动 | 直接构造 `WeatherService`，mock RestClient。`WeatherService` 仍存在，只是加了条件注解。 |
| `WeatherToolsTest` | 无改动 | mock 的是 `WeatherService` 具体类。接口提取后，`WeatherTools` 依赖 `WeatherProvider` 接口，但 mock `WeatherService`（实现了接口）仍然有效。如果编译报错（mock 类型不匹配），将 `mock(WeatherService.class)` 改为 `mock(WeatherProvider.class)` 即可。 |

### 6.3 行为兼容性

| 场景 | provider=uapis（默认） | provider=mcp |
|------|----------------------|--------------|
| 城市参数校验 | `normalizeCity()` | 相同的 `normalizeCity()` |
| 数据获取 | uapis.cn REST API | 外部 MCP `get_weather` |
| 返回类型 | `WeatherInfo` | `WeatherInfo`（完全一致） |
| 参数错误异常 | `IllegalArgumentException` | `IllegalArgumentException`（一致） |
| 服务失败异常 | `IllegalStateException` | `IllegalStateException`（一致） |
| 字段缺失容错 | uapis API 返回什么就映射什么 | `temperature`/`humidity` 可空，其他字段空字符串 |

**切换 provider 不影响 `WeatherTools` 工具定义、模型调用、搭配推荐等任何后续流程。**

---

## 七、验证清单

完成实现后逐项自检：

- [ ] `WeatherProvider` 接口编译通过，含 `getCurrentWeather(String city)` 方法
- [ ] `WeatherService` 添加了 `implements WeatherProvider` 和 `@ConditionalOnProperty(matchIfMissing=true)`
- [ ] `WeatherService.getCurrentWeather()` 添加了 `@Override`，方法体不变
- [ ] `McpWeatherProvider` 添加了 `@ConditionalOnProperty(havingValue = "mcp")`
- [ ] `McpWeatherProvider.parse()` 正确映射 8 个字段到 `WeatherInfo`，`temperature`/`humidity` 可空
- [ ] `McpWeatherProvider.normalizeCity()` 与 `WeatherService` 中的实现一致
- [ ] `WeatherTools` 的字段和构造函数参数类型改为 `WeatherProvider`
- [ ] `WeatherTools` 方法体不变（`weatherService.getCurrentWeather(city)` 调用不变）
- [ ] 默认配置下只有 `WeatherService` 激活
- [ ] 设置 `app.weather.provider=mcp` 后只有 `McpWeatherProvider` 激活
- [ ] `application.properties` 追加了天气 provider 配置项 + SSE 注释
- [ ] `McpWeatherProviderTest` 9 个测试全部通过
- [ ] `WeatherProviderSelectionTest` 2 个测试全部通过
- [ ] 原有 `WeatherServiceTest` 仍通过
- [ ] 原有 `WeatherToolsTest` 仍通过（如编译报错，将 `mock(WeatherService.class)` 改为 `mock(WeatherProvider.class)`）
- [ ] `ToolRegistry` 白名单中 `get_current_weather` 工具名仍匹配
- [ ] 项目整体 `mvn compile` 通过（如果 Maven 可用）
