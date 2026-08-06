package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.mcp.McpConnectionManager;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.config.FashionCutoutProperties;
import com.example.ykdsummer.fashion.domain.ClothingCandidate;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.example.ykdsummer.fashion.domain.ClothingCandidateStatus;
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
    private final McpConnectionManager mcp = mock(McpConnectionManager.class);
    private final McpGarmentCutoutService service =
            new McpGarmentCutoutService(mcp, imageStore, properties);

    {
        when(mcp.callTool(anyString(), anyString())).thenAnswer(invocation -> {
            ToolCallback tool = McpToolSupport.findTool(provider, invocation.getArgument(0));
            return tool == null ? null : tool.call(invocation.getArgument(1));
        });
    }

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
