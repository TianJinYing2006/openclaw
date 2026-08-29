package com.example.ykdsummer.fashion.wardrobe.tool;

import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.wardrobe.application.FashionItemNamer;
import com.example.ykdsummer.fashion.wardrobe.application.FashionSemanticSearchService;
import com.example.ykdsummer.fashion.wardrobe.domain.SemanticWardrobeMatch;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeItem;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Natural-language wardrobe retrieval backed by Bailian embeddings and user-isolated Qdrant vectors. */
@AgentTool
@Component
@ConditionalOnBean(FashionSemanticSearchService.class)
public class FashionSemanticTools implements AiTool {
    private final FashionSemanticSearchService semanticSearch;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionSemanticTools(
            FashionSemanticSearchService semanticSearch,
            ToolArtifactCollector artifacts,
            AiTraceLogger trace
    ) {
        this.semanticSearch = semanticSearch;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "search_wardrobe_semantic", description = "当用户用自然语言描述想找的个人衣物、穿搭意图或相似特征时调用，"
            + "例如“找适合夏天面试的上衣”“我有哪些偏休闲的浅色衣服”“找和牛仔裤好搭的单品”。"
            + "该工具使用语义相似度检索当前微信用户的已确认衣橱，并可叠加精确条件。"
            + "纯粹查看全部衣橱或明确的简单颜色/类目筛选仍可使用 search_wardrobe。")
    public String searchWardrobeSemantic(
            @ToolParam(description = "用户原始的衣橱检索意图，保留场景、季节、风格和搭配含义。") String query,
            @ToolParam(required = false, description = "可选精确类目，例如 外套、牛仔裤、T_SHIRT。") String categoryCode,
            @ToolParam(required = false, description = "可选精确颜色，例如 灰色、黑色。") String color,
            @ToolParam(required = false, description = "可选精确风格标签；多个值必须同时满足。") List<String> styleTags,
            @ToolParam(required = false, description = "可选精确版型，例如 宽松、修身、直筒。") String fitCode,
            @ToolParam(required = false, description = "可选精确图案，例如 纯色、条纹。") String patternCode,
            @ToolParam(required = false, description = "可选精确季节标签；多个值必须同时满足。") List<String> seasonTags,
            @ToolParam(required = false, description = "可选精确场景标签；多个值必须同时满足。") List<String> occasionTags,
            @ToolParam(required = false, description = "可选精确材质，例如 棉、牛仔。") String material,
            @ToolParam(required = false, description = "返回数量，1 到 20；默认 8。") Integer limit
    ) {
        String userId = currentUser();
        if (userId == null) return "当前会话身份不可用，暂时不能检索个人衣橱。";
        String cleanedQuery = safe(query);
        if (cleanedQuery.isBlank()) return "请提供要查找的衣服特征或穿搭意图。";
        int boundedLimit = limit == null ? 8 : Math.max(1, Math.min(limit, 20));
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from(
                categoryCode, color, styleTags, fitCode, patternCode, seasonTags, occasionTags, material);
        trace.toolCall("search_wardrobe_semantic",
                "query=" + cleanedQuery + ", criteria=" + criteria.summary() + ", limit=" + boundedLimit);
        try {
            List<SemanticWardrobeMatch> matches = semanticSearch.search(userId, cleanedQuery, criteria, boundedLimit);
            String result = describe(matches, criteria);
            trace.toolResult("search_wardrobe_semantic", result);
            return result;
        } catch (RuntimeException failure) {
            trace.toolFailure("search_wardrobe_semantic", failure);
            return "个人衣橱语义检索失败，请稍后再试。";
        }
    }

    private String currentUser() {
        String userId = artifacts.userId();
        return userId == null || userId.isBlank() || "unknown".equals(userId) ? null : userId;
    }

    private static String describe(List<SemanticWardrobeMatch> matches, WardrobeSearchCriteria criteria) {
        if (matches.isEmpty()) {
            return criteria.hasFilters()
                    ? "当前衣橱没有同时符合语义意图和精确条件的单品：" + criteria.summary() + "。"
                    : "当前衣橱没有找到与这个描述相近的已确认单品。";
        }
        boolean fallback = matches.stream().allMatch(match -> match.score() < 0d);
        StringBuilder result = new StringBuilder(
                "以下结果仅供 Agent 后续选择；内部 wardrobeItemId 和匹配分数不要展示给用户：\n");
        for (SemanticWardrobeMatch match : matches) {
            WardrobeItem item = match.item();
            result.append("- wardrobeItemId=").append(item.id())
                    .append("；名称=").append(displayName(item))
                    .append("；类目=").append(item.categoryCode());
            if (!safe(item.colorPrimary()).isBlank()) result.append("；主色=").append(item.colorPrimary());
            if (!item.styleTags().isEmpty()) result.append("；风格=").append(String.join("、", item.styleTags()));
            if (!item.seasonTags().isEmpty()) result.append("；季节=").append(String.join("、", item.seasonTags()));
            if (!item.occasionTags().isEmpty()) result.append("；场景=").append(String.join("、", item.occasionTags()));
            if (!fallback) result.append("；匹配分数=").append(String.format(java.util.Locale.ROOT, "%.3f", match.score()));
            result.append('\n');
        }
        if (fallback) result.append("语义服务暂不可用，本次已自动降级为 MySQL 结构化筛选。");
        return result.toString().strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String displayName(WardrobeItem item) {
        String saved = safe(item.displayName());
        return saved.isBlank() ? FashionItemNamer.nameFor(item.categoryCode(), item.colorPrimary(),
                item.attributesJson(), item.fitCode(), item.patternCode()) : saved;
    }
}
