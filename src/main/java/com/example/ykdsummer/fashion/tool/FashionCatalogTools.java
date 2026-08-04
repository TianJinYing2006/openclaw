package com.example.ykdsummer.fashion.tool;

import com.example.ykdsummer.ai.orchestration.AgentTool;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import com.example.ykdsummer.fashion.application.FashionCatalogService;
import com.example.ykdsummer.fashion.domain.FashionProduct;
import com.example.ykdsummer.fashion.domain.FashionProductSearch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Agent-facing read-only catalog search. The model cannot change product inventory or prices. */
@AgentTool
@Component
@ConditionalOnBean(FashionCatalogService.class)
public class FashionCatalogTools implements AiTool {
    private final FashionCatalogService catalog;
    private final AiTraceLogger trace;

    public FashionCatalogTools(FashionCatalogService catalog, AiTraceLogger trace) {
        this.catalog = catalog;
        this.trace = trace;
    }

    @AgentTool(enabled = false)
    @Tool(name = "search_fashion_products", description = "当用户需要购买建议、询问有什么商品、现有衣橱缺少单品，"
            + "或需要为一套穿搭召回在售商品候选时调用。只返回商品橱窗中状态为 ACTIVE 的真实结构化商品；"
            + "不能编造商品、价格、库存或图片。该工具用于候选召回，最终搭配理由仍需结合用户画像、衣橱、天气和场合。")
    public String searchFashionProducts(
            @ToolParam(required = false, description = "可选类目，例如 T_SHIRT、SHIRT、KNITWEAR、JACKET、JEANS、STRAIGHT_PANTS、SNEAKERS、LOAFERS、BAG；也可用中文。") String categoryCode,
            @ToolParam(required = false, description = "可选关键词，匹配商品名称、品牌或描述。") String keyword,
            @ToolParam(required = false, description = "可选主色，例如 黑色、白色、藏青色。") String color,
            @ToolParam(required = false, description = "可选风格标签，例如 简约、通勤、休闲、工装。") String styleTag,
            @ToolParam(required = false, description = "可选季节标签，例如 夏季、春秋、秋冬、四季。") String seasonTag,
            @ToolParam(required = false, description = "可选场合标签，例如 通勤、面试、约会、日常、出行。") String occasionTag,
            @ToolParam(required = false, description = "可选最低价格，单位人民币。") BigDecimal minPrice,
            @ToolParam(required = false, description = "可选最高价格，单位人民币。") BigDecimal maxPrice,
            @ToolParam(required = false, description = "最多返回多少个候选，1 到 12；默认 6。") Integer limit
    ) {
        String category = normalizeCategory(categoryCode);
        int safeLimit = limit == null ? 6 : Math.max(1, Math.min(limit, 12));
        trace.toolCall("search_fashion_products", "category=" + category + ", style=" + safe(styleTag)
                + ", occasion=" + safe(occasionTag) + ", limit=" + safeLimit);
        try {
            List<FashionProduct> products = catalog.searchActiveProducts(new FashionProductSearch(
                    keyword, category, color, styleTag, seasonTag, occasionTag, minPrice, maxPrice, safeLimit));
            String result = describe(products);
            trace.toolResult("search_fashion_products", result);
            return result;
        } catch (IllegalArgumentException failure) {
            trace.toolFailure("search_fashion_products", failure);
            return "商品检索条件无效：请检查价格范围和类目后再试。";
        } catch (RuntimeException failure) {
            trace.toolFailure("search_fashion_products", failure);
            return "商品橱窗暂时无法查询，请稍后再试。";
        }
    }

    private static String describe(List<FashionProduct> products) {
        if (products == null || products.isEmpty()) return "当前商品橱窗没有符合条件的在售单品。不要编造商品；可放宽条件或优先使用用户现有衣橱。";
        StringBuilder result = new StringBuilder("商品橱窗候选：\n");
        for (FashionProduct product : products) {
            result.append("- [").append(product.productCode()).append("] ").append(product.title())
                    .append("，¥").append(product.price().stripTrailingZeros().toPlainString());
            if (!safe(product.brand()).isBlank()) result.append("，品牌 ").append(product.brand());
            result.append("，类目 ").append(product.subCategoryCode());
            if (!safe(product.colorPrimary()).isBlank()) result.append("，").append(product.colorPrimary());
            if (!product.styleTags().isEmpty()) result.append("，风格 ").append(String.join("、", product.styleTags()));
            if (!product.seasonTags().isEmpty()) result.append("，季节 ").append(String.join("、", product.seasonTags()));
            if (!product.occasionTags().isEmpty()) result.append("，场景 ").append(String.join("、", product.occasionTags()));
            if (!safe(product.sourceUrl()).isBlank()) result.append("，链接 ").append(product.sourceUrl());
            result.append('\n');
        }
        return result.toString().strip();
    }

    private static String normalizeCategory(String input) {
        String raw = safe(input);
        if (raw.isBlank()) return "";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "上装", "TOP" -> "TOP";
            case "T恤", "短袖", "T_SHIRT" -> "T_SHIRT";
            case "衬衫", "SHIRT" -> "SHIRT";
            case "针织衫", "毛衣", "KNITWEAR" -> "KNITWEAR";
            case "外套", "OUTERWEAR" -> "OUTERWEAR";
            case "夹克", "JACKET" -> "JACKET";
            case "下装", "BOTTOM" -> "BOTTOM";
            case "裤子", "长裤", "直筒裤", "STRAIGHT_PANTS" -> "STRAIGHT_PANTS";
            case "牛仔裤", "JEANS" -> "JEANS";
            case "鞋", "鞋子", "鞋履", "SHOES" -> "SHOES";
            case "运动鞋", "SNEAKERS" -> "SNEAKERS";
            case "乐福鞋", "LOAFERS" -> "LOAFERS";
            case "包", "包袋", "BAG" -> "BAG";
            default -> raw.toUpperCase(Locale.ROOT);
        };
    }

    private static String safe(String value) { return value == null ? "" : value.replace('\u0000', ' ').strip(); }
}
