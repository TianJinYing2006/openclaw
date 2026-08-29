package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.mcp.McpConnectionManager;
import com.wechatbot.fashion.ai.mcp.McpToolSupport;
import com.wechatbot.fashion.ai.service.AiGatewayException;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore;
import com.wechatbot.fashion.ai.service.LocalImageAssetStore.StoredImage;
import com.wechatbot.fashion.wardrobe.config.FashionAnalysisProperties;
import com.wechatbot.fashion.wardrobe.domain.ClothingCompletenessStatus;
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
    private final McpConnectionManager mcp = mock(McpConnectionManager.class);
    private final McpWardrobePhotoAnalyzer analyzer =
            new McpWardrobePhotoAnalyzer(mcp, imageStore, properties);

    {
        when(mcp.callTool(anyString(), anyString())).thenAnswer(invocation -> {
            ToolCallback tool = McpToolSupport.findTool(provider, invocation.getArgument(0));
            return tool == null ? null : tool.call(invocation.getArgument(1));
        });
    }

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
        assertThat(result.summary()).contains("Unable to read structured clothing candidates");
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
        assertThat(analyzer.promptVersion()).isEqualTo("mcp-v2");

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
