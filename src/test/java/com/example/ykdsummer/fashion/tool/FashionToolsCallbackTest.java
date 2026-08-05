package com.example.ykdsummer.fashion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionCoreService;
import com.example.ykdsummer.fashion.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionToolsCallbackTest {

    @Test
    void readsOnlyTheCurrentUsersFashionDataAndAddsConfirmedImageToWardrobe() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        WardrobeItem item = item(17L, "JEANS", "深蓝", List.of("简约", "通勤"), List.of("春季", "秋季"), List.of("通勤"));
        when(service.profile("managed:instance-a:wechat-user")).thenReturn(new FashionUserProfile(
                9L, "女", "简约通勤", new BigDecimal("300"), new BigDecimal("800"), List.of("通勤", "面试"),
                70, null, Instant.now(), Instant.now()));
        when(service.preferences("managed:instance-a:wechat-user")).thenReturn(List.of());
        when(service.searchWardrobeItems(eq("managed:instance-a:wechat-user"), any(WardrobeSearchCriteria.class), eq(5)))
                .thenReturn(List.of(item));
        when(service.addWardrobeItemWithImage(eq("managed:instance-a:wechat-user"), any(), eq("img_top_1"), eq(2)))
                .thenReturn(item);

        String profile = callback(tools, "get_fashion_profile").call("{}");
        String wardrobe = callback(tools, "search_wardrobe").call("""
                {"categoryCode":"牛仔裤","color":"深蓝","styleTags":["简约","通勤"],"fitCode":"宽松",
                 "seasonTags":["春季","秋季"],"occasionTags":["通勤"],"material":"牛仔","limit":5}
                """);
        String added = callback(tools, "add_wardrobe_item").call("""
                {"categoryCode":"T恤","colorPrimary":"白色","styleTags":["简约"],
                 "seasonTags":["夏季"],"occasionTags":["通勤"],"imageAssetId":"img_top_1","imageVersion":2}
                """);

        assertThat(profile).contains("简约通勤", "300 - 800", "通勤");
        assertThat(wardrobe).contains("藏青色牛仔裤", "简约", "wardrobeItemId=17")
                .doesNotContain("JEANS", "#17");
        assertThat(added).contains("已加入个人衣橱", "已关联展示图").doesNotContain("img_top_1");
        verify(service).addWardrobeItemWithImage(eq("managed:instance-a:wechat-user"), any(), eq("img_top_1"), eq(2));
        ArgumentCaptor<WardrobeSearchCriteria> criteria = ArgumentCaptor.forClass(WardrobeSearchCriteria.class);
        verify(service).searchWardrobeItems(eq("managed:instance-a:wechat-user"), criteria.capture(), eq(5));
        assertThat(criteria.getValue().categoryCodes()).contains("JEANS");
        assertThat(criteria.getValue().color()).isEqualTo("NAVY");
        assertThat(criteria.getValue().styleTags()).containsExactlyInAnyOrder("MINIMAL", "COMMUTE");
        assertThat(criteria.getValue().material()).isEqualTo("DENIM");
        collector.finish();
    }

    @Test
    void refusesToWriteWhenTheToolHasNoCurrentUser() {
        FashionTools tools = new FashionTools(mock(FashionCoreService.class), new ToolArtifactCollector(), AiTraceLogger.disabled());

        assertThat(callback(tools, "add_wardrobe_item").call("{\"categoryCode\":\"T_SHIRT\"}"))
                .contains("当前会话身份不可用");
    }

    @Test
    void mapsPantsCategoryToValidTaxonomyBeforeSaving() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        WardrobeItem item = item(18L, "STRAIGHT_PANTS", "黑色", List.of("休闲"), List.of(), List.of());
        when(service.addWardrobeItem(eq("managed:instance-a:wechat-user"), any()))
                .thenReturn(item);

        String added = callback(tools, "add_wardrobe_item").call(
                "{\"categoryCode\":\"PANTS\",\"colorPrimary\":\"黑色\",\"styleTags\":[\"休闲\"]}");

        ArgumentCaptor<WardrobeItemDraft> draft = ArgumentCaptor.forClass(WardrobeItemDraft.class);
        verify(service).addWardrobeItem(eq("managed:instance-a:wechat-user"), draft.capture());
        assertThat(draft.getValue().categoryCode()).isEqualTo("STRAIGHT_PANTS");
        assertThat(draft.getValue().parentCategoryCode()).isEqualTo("BOTTOM");
        assertThat(added).contains("已加入个人衣橱").contains("暂未关联展示图");
    }

    @Test
    void deletesWardrobeItemWhenUserExplicitlyAsks() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        when(service.archiveWardrobeItem("managed:instance-a:wechat-user", 17L)).thenReturn(true);

        String result = callback(tools, "delete_wardrobe_item").call("{\"wardrobeItemId\":17}");

        assertThat(result).contains("已把这件衣服从衣橱中移除").doesNotContain("17");
        verify(service).archiveWardrobeItem("managed:instance-a:wechat-user", 17L);
        collector.finish();
    }

    @Test
    void reportsWhenDeletingAnItemAlreadyRemovedFromWardrobe() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        when(service.archiveWardrobeItem("managed:instance-a:wechat-user", 99L)).thenReturn(false);

        String result = callback(tools, "delete_wardrobe_item").call("{\"wardrobeItemId\":99}");

        assertThat(result).contains("已不在当前衣橱中");
        collector.finish();
    }

    @Test
    void purgesWardrobeItemWhenUserExplicitlyAsksForPermanentDeletion() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        when(service.purgeWardrobeItem("managed:instance-a:wechat-user", 17L)).thenReturn(List.of("img_asset_1"));

        String result = callback(tools, "purge_wardrobe_item").call("{\"wardrobeItemId\":17}");

        assertThat(result).contains("彻底删除").doesNotContain("img_asset_1");
        verify(service).purgeWardrobeItem("managed:instance-a:wechat-user", 17L);
        collector.finish();
    }

    @Test
    void reportsWhenPurgeIsRejectedDueToTryOnHistory() {
        FashionCoreService service = mock(FashionCoreService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTools tools = new FashionTools(service, collector, AiTraceLogger.disabled());
        when(service.purgeWardrobeItem("managed:instance-a:wechat-user", 18L))
                .thenThrow(new IllegalArgumentException("这件衣服存在试穿或搭配推荐记录，不能彻底删除"));

        String result = callback(tools, "purge_wardrobe_item").call("{\"wardrobeItemId\":18}");

        assertThat(result).contains("无法彻底删除");
        collector.finish();
    }

    private static WardrobeItem item(long id, String category, String color, List<String> styles, List<String> seasons, List<String> occasions) {
        return new WardrobeItem(id, 9L, "instance-a", category, color, List.of(), styles, "", "", seasons, occasions,
                "", "ACTIVE", "PENDING", 0, null, "USER_CONFIRMED", "", Instant.now(), Instant.now());
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
