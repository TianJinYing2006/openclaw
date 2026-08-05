package com.example.ykdsummer.fashion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.fashion.ReferenceImageResolver;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionVirtualTryOnService;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionTryOnToolsCallbackTest {

    @Test
    void submitsOnlyForTheCurrentChatUserAndDoesNotExposeInternalTaskId() {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled());
        when(service.submit("managed:instance-a:wechat-user", 42L)).thenReturn(task(FashionTryOnTaskStatus.SUBMITTED));

        String result = callback(tools, "virtual_try_on_wardrobe_item").call("{\"wardrobeItemId\":42}");

        assertThat(result).contains("后台").doesNotContain("task-tryon-internal");
        verify(service).submit(eq("managed:instance-a:wechat-user"), eq(42L));
        collector.finish();
    }

    @Test
    void reportsPersistedFailureOnlyWhenStatusToolConfirmsIt() {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("user-b");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled());
        when(service.latest("user-b")).thenReturn(java.util.Optional.of(task(FashionTryOnTaskStatus.FAILED)));

        assertThat(callback(tools, "check_virtual_tryon_status").call("{}")).contains("没有生成成功", "可以重新试一次");
        collector.finish();
    }

    @Test
    void submitsFullOutfitAsSingleCollageWhenNoGarmentTypeGiven() throws java.io.IOException {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        FashionTryOnTools.ReferenceImageDownloader downloader = mock(FashionTryOnTools.ReferenceImageDownloader.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled(), resolver);
        tools.setDownloader(downloader);
        when(resolver.garmentsFor("010")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("bottom", "010_2_bottom.png",
                        "https://cdn.example.com/010_2_bottom.png"),
                new ReferenceImageResolver.GarmentImage("top", "010_1_top.png",
                        "https://cdn.example.com/010_1_top.png")));
        when(downloader.download(any(), any())).thenReturn(png());
        when(service.submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("FULL_OUTFIT")))
                .thenReturn(task(FashionTryOnTaskStatus.SUBMITTED));

        String result = callback(tools, "virtual_try_on_reference_outfit").call("{\"referenceOutfitId\":\"010\"}");

        // 未指明单品 → 整套试穿：上衣 + 下装拼成一张穿搭图，作为单件一次提交，最终回一张全套图
        assertThat(result).contains("整套试穿");
        verify(service).submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("FULL_OUTFIT"));
        collector.finish();
    }

    @Test
    void fallsBackToSingleGarmentWhenOutfitCollageCannotDecode() throws java.io.IOException {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        FashionTryOnTools.ReferenceImageDownloader downloader = mock(FashionTryOnTools.ReferenceImageDownloader.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled(), resolver);
        tools.setDownloader(downloader);
        when(resolver.garmentsFor("010")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("bottom", "010_2_bottom.png",
                        "https://cdn.example.com/010_2_bottom.png"),
                new ReferenceImageResolver.GarmentImage("top", "010_1_top.png",
                        "https://cdn.example.com/010_1_top.png")));
        when(downloader.download(any(), any())).thenReturn(new byte[]{1, 2, 3});
        when(service.submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("T_SHIRT")))
                .thenReturn(task(FashionTryOnTaskStatus.SUBMITTED));

        String result = callback(tools, "virtual_try_on_reference_outfit").call("{\"referenceOutfitId\":\"010\"}");

        // 拼图无法解码 → 降级单件试穿上衣，仍能拿到上身效果
        assertThat(result).contains("后台");
        verify(service).submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("T_SHIRT"));
        collector.finish();
    }

    @Test
    void submitsOnlyRequestedGarmentTypeWhenSpecified() throws java.io.IOException {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        FashionTryOnTools.ReferenceImageDownloader downloader = mock(FashionTryOnTools.ReferenceImageDownloader.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled(), resolver);
        tools.setDownloader(downloader);
        when(resolver.garmentsFor("010")).thenReturn(List.of(
                new ReferenceImageResolver.GarmentImage("bottom", "010_2_bottom.png",
                        "https://cdn.example.com/010_2_bottom.png"),
                new ReferenceImageResolver.GarmentImage("top", "010_1_top.png",
                        "https://cdn.example.com/010_1_top.png")));
        when(downloader.download(eq("https://cdn.example.com/010_1_top.png"), any()))
                .thenReturn(new byte[]{1, 2, 3});
        when(service.submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("T_SHIRT")))
                .thenReturn(task(FashionTryOnTaskStatus.SUBMITTED));

        String result = callback(tools, "virtual_try_on_reference_outfit")
                .call("{\"referenceOutfitId\":\"010\",\"garmentType\":\"top\"}");

        // 明确指定上衣 → 只试穿 top 一件
        assertThat(result).contains("后台");
        verify(service).submitWithReferenceOutfit(eq("managed:instance-a:wechat-user"), eq("010"),
                org.mockito.ArgumentMatchers.<byte[]>any(), eq("T_SHIRT"));
        collector.finish();
    }

    @Test
    void reportsWhenReferenceOutfitHasNoUsableGarmentImage() {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ReferenceImageResolver resolver = mock(ReferenceImageResolver.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("user-c");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled(), resolver);
        when(resolver.garmentsFor("999")).thenReturn(List.of());

        String result = callback(tools, "virtual_try_on_reference_outfit").call("{\"referenceOutfitId\":\"999\"}");

        assertThat(result).contains("没有可用于试穿的单品图");
        collector.finish();
    }

    private static FashionTryOnTask task(FashionTryOnTaskStatus status) {
        Instant now = Instant.now();
        return new FashionTryOnTask("task-tryon-internal", 1L, "", "template", 42L, 2L, 3L,
                "wardrobe", null, null,
                status, 0, null, "图片服务超时", null, now, now, now);
    }

    /** 生成可解码的最小 PNG，供拼图路径测试使用。 */
    private static byte[] png() throws java.io.IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }
}
