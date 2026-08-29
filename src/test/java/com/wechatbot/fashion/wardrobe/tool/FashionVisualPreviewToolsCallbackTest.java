package com.wechatbot.fashion.wardrobe.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechatbot.fashion.ai.model.AiArtifact;
import com.wechatbot.fashion.ai.service.AiTraceLogger;
import com.wechatbot.fashion.ai.tool.ToolArtifactCollector;
import com.wechatbot.fashion.wardrobe.application.FashionVisualPreviewService;
import com.wechatbot.fashion.wardrobe.domain.WardrobeItem;
import com.wechatbot.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.wechatbot.fashion.wardrobe.runtime.FashionWardrobePreviewSelectionStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionVisualPreviewToolsCallbackTest {

    @Test
    void sendsTheCurrentUsersActiveTemplateAsAnImageArtifact() {
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionVisualPreviewTools tools = new FashionVisualPreviewTools(previews, new FashionWardrobePreviewSelectionStore(),
                collector, AiTraceLogger.disabled());
        when(previews.currentTemplate("managed:instance-a:wechat-user")).thenReturn(Optional.of(
                new FashionVisualPreviewService.TemplatePreview("通勤模板", "img_template", 2, new byte[]{1, 2, 3})));

        String message = callback(tools, "show_current_tryon_template").call("{}");

        List<AiArtifact> artifacts = collector.finish();
        assertThat(message).contains("已把当前启用的试衣模板照片发给你", "通勤模板");
        assertThat(artifacts).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
            assertThat(artifact.assetId()).isEqualTo("img_template");
            assertThat(artifact.version()).isEqualTo(2);
        });
        verify(previews).currentTemplate("managed:instance-a:wechat-user");
    }

    @Test
    void filtersTheCurrentUsersWardrobeAndReturnsOneCompositeImage() throws Exception {
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionVisualPreviewTools tools = new FashionVisualPreviewTools(previews, new FashionWardrobePreviewSelectionStore(),
                collector, AiTraceLogger.disabled());
        when(previews.wardrobeItems(eq("managed:instance-a:wechat-user"), any(WardrobeSearchCriteria.class), eq(2)))
                .thenReturn(List.of(
                        preview(17L, "JEANS", "NAVY", Color.BLUE),
                        preview(18L, "JACKET", "GRAY", Color.GRAY)));

        String message = callback(tools, "show_wardrobe_items").call("""
                {"categoryCode":"裤子","color":"深蓝","styleTags":["通勤"],"limit":2}
                """);

        ArgumentCaptor<WardrobeSearchCriteria> criteria = ArgumentCaptor.forClass(WardrobeSearchCriteria.class);
        verify(previews).wardrobeItems(eq("managed:instance-a:wechat-user"), criteria.capture(), eq(2));
        assertThat(criteria.getValue().categoryCodes()).contains("JEANS", "STRAIGHT_PANTS");
        assertThat(criteria.getValue().color()).isEqualTo("NAVY");
        assertThat(criteria.getValue().styleTags()).contains("COMMUTE");
        assertThat(message).contains("已发你 1 页衣橱图片", "想看、试穿或筛选哪件");
        List<AiArtifact> artifacts = collector.finish();
        assertThat(artifacts).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.IMAGE);
            assertThat(artifact.bytes()).isNotEmpty();
            assertThat(artifact.assetId()).isBlank();
        });
    }

    @Test
    void mapsAPreviewPositionToTheExactWardrobeItemWithoutModelGuessing() throws Exception {
        FashionVisualPreviewService previews = mock(FashionVisualPreviewService.class);
        FashionWardrobePreviewSelectionStore selections = new FashionWardrobePreviewSelectionStore();
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("user-preview");
        FashionVisualPreviewTools tools = new FashionVisualPreviewTools(previews, selections, collector, AiTraceLogger.disabled());
        when(previews.wardrobeItems(eq("user-preview"), any(WardrobeSearchCriteria.class), eq(5))).thenReturn(List.of(
                preview(11L, "T_SHIRT", "WHITE", Color.WHITE), preview(12L, "SHIRT", "BLUE", Color.BLUE),
                preview(13L, "JEANS", "NAVY", Color.CYAN), preview(14L, "JACKET", "BLACK", Color.BLACK),
                preview(15L, "SHOES", "BROWN", Color.ORANGE)));

        callback(tools, "show_wardrobe_items").call("{\"limit\":5}");
        String selection = callback(tools, "select_wardrobe_preview_item")
                .call("{\"pageNumber\":2,\"position\":\"左上\"}");

        assertThat(collector.finish()).hasSize(2);
        assertThat(selection).contains("已锁定第2页左上的衣橱单品", "wardrobeItemId=15");
    }

    private static FashionVisualPreviewService.WardrobePreview preview(long id, String category, String color, Color fill)
            throws Exception {
        WardrobeItem item = new WardrobeItem(id, 9L, "instance-a", category, color, List.of(), List.of("COMMUTE"),
                "", "", List.of(), List.of(), "", "ACTIVE", "SUCCEEDED", 1, null,
                "USER_CONFIRMED", "", Instant.now(), Instant.now());
        return new FashionVisualPreviewService.WardrobePreview(item, "img_" + id, 1, image(fill));
    }

    private static byte[] image(Color color) throws Exception {
        BufferedImage image = new BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }
}
