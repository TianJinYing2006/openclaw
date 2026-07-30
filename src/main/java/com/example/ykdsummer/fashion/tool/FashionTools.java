package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionCoreService;
import com.example.ykdsummer.fashion.application.FashionItemNamer;
import com.example.ykdsummer.fashion.domain.FashionUserPreference;
import com.example.ykdsummer.fashion.domain.FashionUserProfile;
import com.example.ykdsummer.fashion.domain.WardrobeItem;
import com.example.ykdsummer.fashion.domain.WardrobeItemDraft;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Agent boundary for persistent Fashion data. It always derives identity from the current iLink request,
 * never accepts a user id from the model.
 */
@Component
@ConditionalOnBean(FashionCoreService.class)
public class FashionTools implements AiTool {
    private final FashionCoreService fashion;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionTools(FashionCoreService fashion, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.fashion = fashion;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "get_fashion_profile", description = "当用户询问自己的穿搭风格、预算、常见场景、偏好，"
            + "或要基于历史衣橱给出穿搭建议前调用。只返回当前微信会话用户的长期画像和已确认偏好。")
    public String getFashionProfile() {
        String userId = currentUser();
        if (userId == null) return unavailableIdentity();
        trace.toolCall("get_fashion_profile", "current user");
        try {
            FashionUserProfile profile = fashion.profile(userId);
            List<FashionUserPreference> preferences = fashion.preferences(userId);
            String result = describeProfile(profile, preferences);
            trace.toolResult("get_fashion_profile", result);
            return result;
        } catch (RuntimeException failure) {
            return failed("get_fashion_profile", failure, "读取穿搭画像失败，请稍后再试。");
        }
    }

    @Tool(name = "search_wardrobe", description = "当用户询问个人衣橱、已有衣服，或要求按多个条件筛选时调用。"
            + "所有非空条件必须同时满足：类目、颜色、风格、版型、图案、季节、场景、材质。"
            + "categoryCode 可以传中文或标准代码，例如 牛仔裤/JEANS、外套/OUTERWEAR、裤子、鞋；"
            + "颜色会匹配主色和次色。不得把用户没有提到的筛选条件擅自补上。")
    public String searchWardrobe(
            @ToolParam(required = false, description = "可选类目，例如 牛仔裤、外套、裤子、鞋，或 JEANS、JACKET、T_SHIRT；为空则不按类目筛选。") String categoryCode,
            @ToolParam(required = false, description = "可选颜色，例如 深蓝、白色、黑色；匹配主色或次色。") String color,
            @ToolParam(required = false, description = "可选风格标签；多个标签必须同时满足，例如 [简约,通勤]。") List<String> styleTags,
            @ToolParam(required = false, description = "可选版型，例如 宽松、修身、直筒。") String fitCode,
            @ToolParam(required = false, description = "可选图案，例如 纯色、条纹、格纹。") String patternCode,
            @ToolParam(required = false, description = "可选季节标签；多个标签必须同时满足，例如 [春季,秋季]。") List<String> seasonTags,
            @ToolParam(required = false, description = "可选适用场景标签；多个标签必须同时满足，例如 [通勤,面试]。") List<String> occasionTags,
            @ToolParam(required = false, description = "可选材质，例如 棉、牛仔。") String material,
            @ToolParam(required = false, description = "最多返回多少条，1 到 20；为空时返回 12 条。") Integer limit
    ) {
        String userId = currentUser();
        if (userId == null) return unavailableIdentity();
        int boundedLimit = limit == null ? 12 : Math.max(1, Math.min(limit, 20));
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from(categoryCode, color, styleTags, fitCode, patternCode,
                seasonTags, occasionTags, material);
        trace.toolCall("search_wardrobe", "criteria=" + criteria.summary() + ", limit=" + boundedLimit);
        try {
            List<WardrobeItem> items = fashion.searchWardrobeItems(userId, criteria, boundedLimit);
            String result = describeWardrobe(items, criteria);
            trace.toolResult("search_wardrobe", result);
            return result;
        } catch (RuntimeException failure) {
            return failed("search_wardrobe", failure, "读取衣橱失败，请稍后再试。");
        }
    }

    @Tool(name = "add_wardrobe_item", description = "仅用于用户明确提供了完整标签、要求手工记录一件衣服时调用。"
            + "不能因为用户只是发送图片或讨论穿搭就自动保存。对服装照片必须先调用 analyze_wardrobe_photo，"
            + "让用户确认候选和抠图后再由 confirm_wardrobe_candidate 入衣橱。")
    public String addWardrobeItem(
            @ToolParam(required = false, description = "用户给这件衣服起的自然中文名称；为空时系统根据标签生成。") String displayName,
            @ToolParam(required = true, description = "服装类目代码，例如 T_SHIRT、SHIRT、JACKET、JEANS、DRESS、SHOES；不确定使用 UNKNOWN。") String categoryCode,
            @ToolParam(required = false, description = "用户明确说明的主色，例如 白色、黑色；未知可为空。") String colorPrimary,
            @ToolParam(required = false, description = "用户明确说明的次要颜色列表；未知可为空数组。") List<String> secondaryColors,
            @ToolParam(required = false, description = "用户明确说明的风格标签列表，例如 简约、通勤；未知可为空数组。") List<String> styleTags,
            @ToolParam(required = false, description = "版型，例如 宽松、直筒；未知可为空。") String fitCode,
            @ToolParam(required = false, description = "图案，例如 纯色、条纹；未知可为空。") String patternCode,
            @ToolParam(required = false, description = "季节标签列表，例如 夏季、春秋；未知可为空数组。") List<String> seasonTags,
            @ToolParam(required = false, description = "适用场景标签列表，例如 通勤、面试；未知可为空数组。") List<String> occasionTags,
            @ToolParam(required = false, description = "材质，例如 棉、牛仔；未知可为空。") String material,
            @ToolParam(required = false, description = "可选的已上传图片编号，必须以 img_ 开头。") String imageAssetId,
            @ToolParam(required = false, description = "可选图片版本；为空时关联该图片的最新版本。") Integer imageVersion,
            @ToolParam(required = false, description = "用户补充备注；未知可为空。") String notes
    ) {
        String userId = currentUser();
        if (userId == null) return unavailableIdentity();
        String category = normalizeCategory(categoryCode);
        if (category.isBlank()) category = "UNKNOWN";
        trace.toolCall("add_wardrobe_item", "category=" + category + ", hasImage=" + !safe(imageAssetId).isBlank());
        try {
            WardrobeItemDraft draft = new WardrobeItemDraft(safe(displayName), parentCategory(category), category,
                    colorPrimary, secondaryColors, styleTags, fitCode, patternCode, seasonTags, occasionTags,
                    material, "USER_CONFIRMED", notes, "1.0.0", "{}", BigDecimal.ONE);
            WardrobeItem item = safe(imageAssetId).isBlank()
                    ? fashion.addWardrobeItem(userId, draft)
                    : fashion.addWardrobeItemWithImage(userId, draft, safe(imageAssetId), imageVersion);
            String result = "已加入个人衣橱：" + describeItem(item)
                    + (safe(imageAssetId).isBlank() ? "。暂未关联展示图。" : "。已关联展示图。");
            trace.toolResult("add_wardrobe_item", result);
            return result;
        } catch (IllegalArgumentException failure) {
            return failed("add_wardrobe_item", failure,
                    "加入衣橱失败：请确认服装类目和图片编号属于当前用户，再重试。");
        } catch (RuntimeException failure) {
            return failed("add_wardrobe_item", failure, "保存衣橱单品失败，请稍后再试。");
        }
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }

    private String unavailableIdentity() {
        return "当前会话身份不可用，暂时不能读取或修改个人衣橱。";
    }

    private String failed(String toolName, RuntimeException failure, String message) {
        trace.toolFailure(toolName, failure);
        return message;
    }

    private static String describeProfile(FashionUserProfile profile, List<FashionUserPreference> preferences) {
        StringBuilder result = new StringBuilder("当前穿搭画像：");
        boolean hasProfile = false;
        if (!safe(profile.genderExpression()).isBlank()) {
            result.append("性别表达 ").append(profile.genderExpression());
            hasProfile = true;
        }
        if (!safe(profile.styleSummary()).isBlank()) {
            appendSeparator(result, hasProfile).append("风格 ").append(profile.styleSummary());
            hasProfile = true;
        }
        if (profile.budgetMin() != null || profile.budgetMax() != null) {
            appendSeparator(result, hasProfile).append("预算 ")
                    .append(profile.budgetMin() == null ? "未设下限" : profile.budgetMin().stripTrailingZeros().toPlainString())
                    .append(" - ")
                    .append(profile.budgetMax() == null ? "未设上限" : profile.budgetMax().stripTrailingZeros().toPlainString());
            hasProfile = true;
        }
        if (!profile.commonOccasions().isEmpty()) {
            appendSeparator(result, hasProfile).append("常见场景 ").append(String.join("、", profile.commonOccasions()));
            hasProfile = true;
        }
        if (!hasProfile) result.append("暂未补充明确资料");
        if (preferences == null || preferences.isEmpty()) {
            result.append("\n已确认偏好：暂无。");
        } else {
            result.append("\n已确认偏好：");
            preferences.forEach(value -> result.append(value.polarity().equals("NEGATIVE") ? "不偏好 " : "偏好 ")
                    .append(value.valueCode()).append("；"));
        }
        return result.toString();
    }

    private static String describeWardrobe(List<WardrobeItem> items, WardrobeSearchCriteria criteria) {
        if (items.isEmpty()) {
            return criteria == null || !criteria.hasFilters()
                    ? "当前衣橱还没有已保存的单品。"
                    : "当前衣橱没有同时符合这些条件的单品：" + criteria.summary() + "。";
        }
        StringBuilder result = new StringBuilder(criteria == null || !criteria.hasFilters()
                ? "已保存衣橱单品：\n" : "符合筛选条件的衣橱单品：\n");
        items.forEach(item -> result.append("- ").append(describeItem(item)).append('\n'));
        return result.toString().strip();
    }

    private static String describeItem(WardrobeItem item) {
        String name = safe(item.displayName());
        if (name.isBlank()) name = FashionItemNamer.nameFor(item.categoryCode(), item.colorPrimary(), item.attributesJson(),
                item.fitCode(), item.patternCode());
        StringBuilder result = new StringBuilder(name);
        if (!item.styleTags().isEmpty()) result.append("，风格 ").append(labels(item.styleTags(), FashionTools::styleLabel));
        if (!item.seasonTags().isEmpty()) result.append("，适合 ").append(labels(item.seasonTags(), FashionTools::seasonLabel));
        if (!item.occasionTags().isEmpty()) result.append("，场景 ").append(labels(item.occasionTags(), FashionTools::occasionLabel));
        if (!safe(item.material()).isBlank()) result.append("，材质 ").append(materialLabel(item.material()));
        return result.toString();
    }

    private static String labels(List<String> values, java.util.function.Function<String, String> mapper) {
        return values.stream().map(mapper).filter(value -> !value.isBlank()).distinct()
                .reduce((left, right) -> left + "、" + right).orElse("");
    }

    private static String styleLabel(String value) {
        return switch (safe(value).toUpperCase(Locale.ROOT)) {
            case "MINIMAL" -> "简约";
            case "CASUAL" -> "休闲";
            case "COMMUTE" -> "通勤";
            default -> safe(value);
        };
    }

    private static String seasonLabel(String value) {
        return switch (safe(value).toUpperCase(Locale.ROOT)) {
            case "SPRING" -> "春季";
            case "SUMMER" -> "夏季";
            case "AUTUMN" -> "秋季";
            case "WINTER" -> "冬季";
            default -> safe(value);
        };
    }

    private static String occasionLabel(String value) {
        return switch (safe(value).toUpperCase(Locale.ROOT)) {
            case "CASUAL" -> "日常休闲";
            case "COMMUTE" -> "通勤";
            case "DATE" -> "约会";
            case "FORMAL" -> "正式场合";
            case "SPORT" -> "运动";
            default -> safe(value);
        };
    }

    private static String materialLabel(String value) {
        return switch (safe(value).toUpperCase(Locale.ROOT)) {
            case "COTTON" -> "棉";
            case "DENIM" -> "牛仔";
            case "KNIT" -> "针织";
            case "WOOL" -> "羊毛";
            case "LEATHER" -> "皮革";
            case "LINEN" -> "亚麻";
            default -> safe(value);
        };
    }

    private static String normalizeCategory(String input) {
        String raw = safe(input);
        if (raw.isBlank()) return "";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "T恤", "短袖", "T_SHIRT" -> "T_SHIRT";
            case "衬衫", "SHIRT" -> "SHIRT";
            case "针织衫", "毛衣", "KNITWEAR" -> "KNITWEAR";
            case "外套", "夹克", "JACKET" -> "JACKET";
            case "裤子", "长裤", "直筒裤", "STRAIGHT_PANTS" -> "STRAIGHT_PANTS";
            case "牛仔裤", "JEANS" -> "JEANS";
            case "裙子", "半身裙", "SKIRT" -> "SKIRT";
            case "连衣裙", "DRESS" -> "DRESS";
            case "鞋", "鞋子", "SHOES" -> "SHOES";
            case "包", "包袋", "BAG" -> "BAG";
            case "未知", "UNKNOWN" -> "UNKNOWN";
            default -> raw.toUpperCase(Locale.ROOT);
        };
    }

    private static String parentCategory(String category) {
        return switch (safe(category).toUpperCase(Locale.ROOT)) {
            case "T_SHIRT", "SHIRT", "KNITWEAR" -> "TOP";
            case "JACKET" -> "OUTERWEAR";
            case "JEANS", "STRAIGHT_PANTS", "SKIRT" -> "BOTTOM";
            case "DRESS" -> "ONE_PIECE";
            case "SNEAKERS", "LOAFERS" -> "SHOES";
            default -> safe(category).toUpperCase(Locale.ROOT);
        };
    }

    private static StringBuilder appendSeparator(StringBuilder value, boolean hasPrevious) {
        if (hasPrevious) value.append("；");
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
