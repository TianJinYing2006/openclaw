package com.wechatbot.fashion.wardrobe.tool;

import com.wechatbot.fashion.ai.model.AiArtifact;
import com.wechatbot.fashion.ai.orchestration.AgentTool;
import com.wechatbot.fashion.ai.service.AiTraceLogger;
import com.wechatbot.fashion.ai.tool.AiTool;
import com.wechatbot.fashion.ai.tool.ToolArtifactCollector;
import com.wechatbot.fashion.wardrobe.application.FashionVisualPreviewService;
import com.wechatbot.fashion.wardrobe.application.FashionWardrobeContactSheet;
import com.wechatbot.fashion.wardrobe.domain.WardrobeItem;
import com.wechatbot.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.wechatbot.fashion.wardrobe.runtime.FashionWardrobePreviewSelectionStore;
import com.wechatbot.fashion.wardrobe.runtime.FashionWardrobePreviewSelectionStore.Selection;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** User-visible image previews for the active try-on template and confirmed wardrobe items. */
@AgentTool
@Component
@ConditionalOnBean(FashionVisualPreviewService.class)
public class FashionVisualPreviewTools implements AiTool {
    private static final int MAX_PREVIEW_ITEMS = 12;
    private static final int PAGE_SIZE = FashionWardrobeContactSheet.PAGE_SIZE;
    private final FashionVisualPreviewService previews;
    private final FashionWardrobePreviewSelectionStore selections;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionVisualPreviewTools(FashionVisualPreviewService previews, FashionWardrobePreviewSelectionStore selections,
                                     ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.previews = previews;
        this.selections = selections;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "show_current_tryon_template", description = "当用户明确要求查看、重新发送或确认当前启用的试衣人物模板照片时调用。"
            + "只会展示当前微信用户当前启用的模板，不展示其他用户图片。")
    public String showCurrentTryonTemplate() {
        String userId = currentUser();
        if (userId == null) return unavailable();
        trace.toolCall("show_current_tryon_template", "current");
        try {
            return previews.currentTemplate(userId).map(preview -> {
                artifacts.add(AiArtifact.image(preview.imageBytes(), "当前试衣模板", preview.assetId(), preview.version()));
                String message = "已把当前启用的试衣模板照片发给你" + named(preview.displayName()) + "。";
                trace.toolResult("show_current_tryon_template", message);
                return message;
            }).orElseGet(() -> {
                String message = "当前还没有可展示的试衣模板。请先上传并保存一张清晰的全身人物照片。";
                trace.toolResult("show_current_tryon_template", message);
                return message;
            });
        } catch (RuntimeException failure) {
            return failed("show_current_tryon_template", failure, "读取当前试衣模板失败，请稍后再试。");
        }
    }

    @Tool(name = "show_wardrobe_items", description = "当用户明确要求看自己的衣橱图片、展示某类衣服或按颜色/风格等条件查看衣物时调用。"
            + "所有非空条件必须同时满足：类目、颜色、风格、版型、图案、季节、场景、材质。"
            + "每页固定 2 x 2，共 4 件；超过 4 件会连续回传下一页，最多展示 12 件。"
            + "用户可按“第2页右下”锁定后续要操作的单品。")
    public String showWardrobeItems(
            @ToolParam(required = false, description = "可选类目，例如 牛仔裤、外套、裤子、鞋，或 JEANS、JACKET、T_SHIRT。") String categoryCode,
            @ToolParam(required = false, description = "可选颜色，例如 深蓝、白色、黑色；匹配主色或次色。") String color,
            @ToolParam(required = false, description = "可选风格标签；多个标签必须同时满足。") List<String> styleTags,
            @ToolParam(required = false, description = "可选版型，例如 宽松、修身、直筒。") String fitCode,
            @ToolParam(required = false, description = "可选图案，例如 纯色、条纹、格纹。") String patternCode,
            @ToolParam(required = false, description = "可选季节标签；多个标签必须同时满足。") List<String> seasonTags,
            @ToolParam(required = false, description = "可选适用场景标签；多个标签必须同时满足。") List<String> occasionTags,
            @ToolParam(required = false, description = "可选材质，例如 棉、牛仔。") String material,
            @ToolParam(required = false, description = "展示数量，1 到 12；为空时展示最多 8 件。") Integer limit
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        int boundedLimit = limit == null ? 8 : Math.max(1, Math.min(limit, MAX_PREVIEW_ITEMS));
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from(categoryCode, color, styleTags, fitCode, patternCode,
                seasonTags, occasionTags, material);
        trace.toolCall("show_wardrobe_items", "criteria=" + criteria.summary() + ", limit=" + boundedLimit);
        try {
            WardrobeDisplay display = prepareWardrobeDisplay(userId, criteria, boundedLimit);
            display.artifacts().forEach(artifacts::add);
            trace.toolResult("show_wardrobe_items", display.message());
            return display.message();
        } catch (RuntimeException failure) {
            return failed("show_wardrobe_items", failure, "读取衣橱展示图失败，请稍后再试。");
        }
    }

    /**
     * Shared by the Agent tool and the deterministic chat command guard. The caller receives the same pages that
     * a normal tool invocation would attach, so the WeChat reply layer can send one image or a batch unchanged.
     */
    public WardrobeDisplay prepareWardrobeDisplay(String userId, WardrobeSearchCriteria criteria, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_PREVIEW_ITEMS));
        WardrobeSearchCriteria safeCriteria = criteria == null
                ? WardrobeSearchCriteria.from(null, null, null, null, null, null, null, null)
                : criteria;
        List<FashionVisualPreviewService.WardrobePreview> matches = previews.wardrobeItems(userId, safeCriteria, boundedLimit);
        if (matches.isEmpty()) {
            String message = safeCriteria.hasFilters()
                    ? "衣橱里没有同时符合这些条件的可展示单品：" + safeCriteria.summary() + "。"
                    : "当前衣橱还没有可展示的已确认单品。";
            return new WardrobeDisplay(message, List.of());
        }
        List<FashionVisualPreviewService.WardrobePreview> withImages = matches.stream()
                .filter(FashionVisualPreviewService.WardrobePreview::hasImage).toList();
        if (withImages.isEmpty()) {
            return new WardrobeDisplay("找到了这些衣橱单品，但它们还没有可用的展示图：\n" + describe(matches), List.of());
        }
        selections.replace(userId, withImages.stream().map(FashionVisualPreviewService.WardrobePreview::item).toList());
        int pages = (int) Math.ceil(withImages.size() / (double) PAGE_SIZE);
        List<AiArtifact> displays = new java.util.ArrayList<>(pages);
        for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
            int from = pageIndex * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, withImages.size());
            byte[] display = FashionWardrobeContactSheet.composePage(withImages.subList(from, to).stream()
                    .map(FashionVisualPreviewService.WardrobePreview::imageBytes).toList(), pageIndex + 1);
            displays.add(AiArtifact.image(display, "衣橱单品展示第" + (pageIndex + 1) + "页", "", 0));
        }
        String message = "已发你 " + pages + " 页衣橱图片，共 " + withImages.size() + " 件。"
                + "想看、试穿或筛选哪件，直接告诉我就行。";
        if (withImages.size() < matches.size()) {
            message += "\n另外有 " + (matches.size() - withImages.size()) + " 件匹配单品尚无可展示图片。";
        }
        return new WardrobeDisplay(message, displays);
    }

    @Tool(name = "select_wardrobe_preview_item", description = "当用户根据刚才衣橱展示图的位置选择、锁定、试穿或操作某件衣服时调用。"
            + "例如“选左上”“锁定第2页右下”“试第1页右上那件”。该工具将位置精确解析为当前用户的衣橱单品，"
            + "不允许模型根据图片猜测；展示记录 30 分钟后过期。")
    public String selectWardrobePreviewItem(
            @ToolParam(required = false, description = "页码；用户没有说页码时传 1。") Integer pageNumber,
            @ToolParam(required = true, description = "格子位置，只能是 左上、右上、左下、右下。") String position
    ) {
        String userId = currentUser();
        if (userId == null) return unavailable();
        int page = pageNumber == null ? 1 : pageNumber;
        trace.toolCall("select_wardrobe_preview_item", "page=" + page + ", position=" + safe(position));
        try {
            return selections.select(userId, page, position).map(selection -> selectedMessage(selection))
                    .orElseGet(() -> {
                        String message = "找不到这个衣橱展示位置。请先让我展示衣橱，或确认页码和位置是否正确。";
                        trace.toolResult("select_wardrobe_preview_item", message);
                        return message;
                    });
        } catch (RuntimeException failure) {
            return failed("select_wardrobe_preview_item", failure, "选择衣橱单品失败，请重新展示后再试。");
        }
    }

    private String selectedMessage(Selection selection) {
        String message = "已锁定第" + selection.pageNumber() + "页" + selection.slot().displayName()
                + "的衣橱单品。内部 wardrobeItemId=" + selection.wardrobeItemId()
                + "；后续若用户要求试穿，可使用此编号调用 virtual_try_on_wardrobe_item。";
        trace.toolResult("select_wardrobe_preview_item", "page=" + selection.pageNumber() + ", slot="
                + selection.slot().displayName() + ", wardrobeItem=" + selection.wardrobeItemId());
        return message;
    }

    private static String describe(List<FashionVisualPreviewService.WardrobePreview> values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            WardrobeItem item = values.get(index).item();
            int page = index / PAGE_SIZE + 1;
            String position = switch (index % PAGE_SIZE) {
                case 0 -> "左上";
                case 1 -> "右上";
                case 2 -> "左下";
                default -> "右下";
            };
            result.append("第").append(page).append("页").append(position).append("：").append(item.categoryCode());
            if (!safe(item.colorPrimary()).isBlank()) result.append("，").append(item.colorPrimary());
            if (!item.styleTags().isEmpty()) result.append("，").append(String.join("、", item.styleTags()));
            if (!safe(item.fitCode()).isBlank()) result.append("，").append(item.fitCode());
            if (index + 1 < values.size()) result.append('\n');
        }
        return result.toString();
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }
    private String failed(String name, RuntimeException failure, String message) {
        trace.toolFailure(name, failure);
        return message;
    }
    private static String unavailable() { return "当前会话身份不可用，暂时不能查看个人衣橱或试衣模板。"; }
    private static String named(String value) { return safe(value).isBlank() ? "" : "（" + safe(value) + "）"; }
    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }

    public record WardrobeDisplay(String message, List<AiArtifact> artifacts) {
        public WardrobeDisplay {
            message = safe(message);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }
}
