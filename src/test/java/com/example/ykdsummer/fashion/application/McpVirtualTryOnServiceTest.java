package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.FashionTryOnProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpVirtualTryOnServiceTest {

    private final FashionTryOnProperties properties = new FashionTryOnProperties();
    private final SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
    private final LocalImageAssetStore assets = mock(LocalImageAssetStore.class);
    private final McpVirtualTryOnService service = new McpVirtualTryOnService(provider, assets, properties);

    @Test
    void refusesWhenPersonOrGarmentImageIsMissing() {
        StoredImage garment = image("img_garment", 2);

        VirtualTryOnService.TryOnResult result =
                service.render("user-a", null, garment, "T_SHIRT", Duration.ofSeconds(150));

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("试衣素材图片不可用");
    }

    @Test
    void failsWhenTheConfiguredToolIsNotExposedByTheMcpServer() {
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        VirtualTryOnService.TryOnResult result = render();

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("试衣 MCP 工具未配置: virtual_try_on");
    }

    @Test
    void decodesImageBase64WhenTheServerReturnsIt() {
        byte[] payload = new byte[]{1, 2, 3};
        ToolCallback tool = tool("virtual_try_on",
                "{\"imageUrl\":\"https://cdn.example/out.png\",\"imageBase64\":\""
                        + Base64.getEncoder().encodeToString(payload) + "\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        VirtualTryOnService.TryOnResult result = render();

        assertThat(result.hasImage()).isTrue();
        assertThat(result.imageBytes()).containsExactly(1, 2, 3);
        assertThat(result.remoteUrl()).isEqualTo("https://cdn.example/out.png");
    }

    @Test
    void surfacesTheServerErrorWhenTheServerReportsFailure() {
        ToolCallback tool = tool("virtual_try_on", "{\"error\":\"model overloaded\"}");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        VirtualTryOnService.TryOnResult result = render();

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("model overloaded");
    }

    @Test
    void rejectsMalformedServerResponses() {
        ToolCallback tool = tool("virtual_try_on", "not-json");
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

        VirtualTryOnService.TryOnResult result = render();

        assertThat(result.hasImage()).isFalse();
        assertThat(result.failureSummary()).isEqualTo("试衣服务返回格式异常");
    }

    @Test
    void downloadsTheResultImageWhenTheServerReturnsAnImageUrl() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/result.png", exchange -> {
            byte[] body = new byte[]{9, 8, 7};
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String resultUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/result.png";
            ToolCallback tool = tool("virtual_try_on", "{\"imageUrl\":\"" + resultUrl + "\"}");
            when(provider.getToolCallbacks()).thenReturn(new ToolCallback[]{tool});

            VirtualTryOnService.TryOnResult result = render();

            assertThat(result.hasImage()).isTrue();
            assertThat(result.imageBytes()).containsExactly(9, 8, 7);
            assertThat(result.remoteUrl()).isEqualTo(resultUrl);
        } finally {
            server.stop(0);
        }
    }

    private VirtualTryOnService.TryOnResult render() {
        StoredImage person = image("img_person", 1);
        StoredImage garment = image("img_garment", 2);
        when(assets.signedReadUrl(person)).thenReturn("https://oss.example/person");
        when(assets.signedReadUrl(garment)).thenReturn("https://oss.example/garment");
        return service.render("user-a", person, garment, "T_SHIRT", Duration.ofSeconds(150));
    }

    private static ToolCallback tool(String name, String response) {
        ToolCallback tool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(response);
        return tool;
    }

    private static StoredImage image(String assetId, int version) {
        return new StoredImage(assetId, version, Path.of("C:/test/" + assetId + ".png"), "", null,
                Instant.now(), "image/png", "generated", "");
    }
}
