package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionReferenceSemanticSearchService;
import com.example.ykdsummer.fashion.application.FashionReferenceService;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.domain.SemanticReferenceMatch;
import com.example.ykdsummer.fashion.domain.WardrobeSearchCriteria;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit public-reference search. Personal wardrobe requests must use the user-scoped wardrobe tools. */
@Component
@ConditionalOnProperty(prefix = "app.fashion.reference", name = "enabled", havingValue = "true")
public class FashionReferenceTools implements AiTool {
    private final FashionReferenceService references;
    private final FashionReferenceSemanticSearchService semantic;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public FashionReferenceTools(FashionReferenceService references,
            ObjectProvider<FashionReferenceSemanticSearchService> semantic,
            ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.references = references;
        this.semantic = semantic.getIfAvailable();
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "search_fashion_references", description = "仅当用户明确要求查看公共穿搭素材、参考款、"
            + "搭配案例或从公共参考库找灵感时调用。普通的‘我有哪些衣服/找我的灰色短袖’必须使用个人衣橱工具，"
            + "不得调用本工具。公共素材不是商品，不得编造价格、库存或购买链接。")
    public String searchFashionReferences(
            @ToolParam(required = false, description = "用户原始的公共穿搭参考需求；可包含风格、季节、场景和搭配语义。") String query,
            @ToolParam(required = false, description = "可选一级或二级类目，例如 上衣、T恤、牛仔裤、外套。") String categoryCode,
            @ToolParam(required = false, description = "可选颜色，例如 灰色、黑色、卡其色。") String color,
            @ToolParam(required = false, description = "可选风格；多个条件必须同时满足。") List<String> styleTags,
            @ToolParam(required = false, description = "可选版型，例如 宽松、直筒。") String fitCode,
            @ToolParam(required = false, description = "可选图案，例如 纯色、条纹、格纹、印花。") String patternCode,
            @ToolParam(required = false, description = "可选季节；多个条件必须同时满足。") List<String> seasonTags,
            @ToolParam(required = false, description = "可选场景；多个条件必须同时满足。") List<String> occasionTags,
            @ToolParam(required = false, description = "可选材质，例如 棉、牛仔、针织。") String material,
            @ToolParam(required = false, description = "返回数量，1 到 4；默认 4。") Integer limit
    ) {
        int bounded = limit == null ? 4 : Math.max(1, Math.min(limit, 4));
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from(categoryCode, color, styleTags, fitCode,
                patternCode, seasonTags, occasionTags, material);
        String cleanedQuery = safe(query);
        trace.toolCall("search_fashion_references", "hasQuery=" + !cleanedQuery.isBlank()
                + ", criteria=" + criteria.summary() + ", limit=" + bounded);
        try {
            List<FashionReferenceLook> looks = semantic != null && !cleanedQuery.isBlank()
                    ? semantic.search(cleanedQuery, criteria, bounded).stream().map(SemanticReferenceMatch::look).toList()
                    : references.search(criteria, bounded);
            if (looks.isEmpty()) return "公共穿搭参考库暂时没有符合这些条件的素材。可以放宽一个条件再找。";
            int images = 0;
            for (FashionReferenceLook look : looks) {
                if (images >= bounded) break;
                byte[] bytes = references.image(look).orElse(null);
                if (bytes != null && bytes.length > 0) {
                    artifacts.add(AiArtifact.image(bytes, look.displayName(), "", 0));
                    images++;
                }
            }
            StringBuilder result = new StringBuilder("公共穿搭参考候选（只向用户展示自然名称，不展示内部编号）：\n");
            for (FashionReferenceLook look : looks) {
                result.append("- ").append(look.displayName()).append("；包含：")
                        .append(look.garments().stream().map(value -> value.displayName())
                                .reduce((left, right) -> left + "、" + right).orElse("服装单品"))
                        .append('\n');
            }
            if (images > 0) result.append("已附带 ").append(images).append(" 张参考图。回复时简短说明即可。");
            String message = result.toString().strip();
            trace.toolResult("search_fashion_references", message);
            return message;
        } catch (RuntimeException failure) {
            trace.toolFailure("search_fashion_references", failure);
            return "公共穿搭参考库暂时无法查询，请稍后再试。";
        }
    }

    private static String safe(String value) { return value == null ? "" : value.replace('\0', ' ').strip(); }
}
