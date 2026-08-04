package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.AiGatewayException;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.ai.mcp.McpToolSupport;
import com.example.ykdsummer.fashion.config.FashionAnalysisProperties;
import com.example.ykdsummer.fashion.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * MCP 适配器：调用外部服装识别 MCP Server 工具替代 ChatCompletions 视觉模型。
 *
 * <p>外部 Server 接收图片签名 URL，返回结构化候选 JSON。本实现负责解析响应并应用与
 * {@link FashionVisionCandidateAnalyzer} 相同的业务规则：类目归一化、质量阈值、显示名生成。</p>
 *
 * <p>不包含 {@code analyzeSavedSummary()} 快速路径——该优化是 ChatCompletions 特有的
 * （复用已保存的图片描述）。MCP Server 自行决定是否缓存；上层 {@code analyzePhoto()} 的
 * 候选 DB 缓存仍然生效。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.fashion.analysis", name = "provider", havingValue = "mcp")
public class McpWardrobePhotoAnalyzer implements WardrobePhotoAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(McpWardrobePhotoAnalyzer.class);
    private static final BigDecimal MINIMUM_USABLE_QUALITY = new BigDecimal("0.45");

    private final SyncMcpToolCallbackProvider toolProvider;
    private final LocalImageAssetStore imageStore;
    private final FashionAnalysisProperties properties;

    public McpWardrobePhotoAnalyzer(SyncMcpToolCallbackProvider toolProvider,
                                    LocalImageAssetStore imageStore,
                                    FashionAnalysisProperties properties) {
        this.toolProvider = toolProvider;
        this.imageStore = imageStore;
        this.properties = properties;
    }

    @Override
    public WardrobePhotoAnalyzer.AnalysisResult analyze(StoredImage source) {
        if (source == null) throw new IllegalArgumentException("source image is required");
        String toolName = properties.getMcp().getToolName();
        try {
            String imageUrl = imageStore.signedReadUrl(source);
            ToolCallback tool = McpToolSupport.findTool(toolProvider, toolName);
            if (tool == null) {
                log.warn("Wardrobe photo analysis MCP tool not found, tool={}", toolName);
                throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
            }
            String jsonArgs = McpToolSupport.objectMapper().createObjectNode()
                    .put("imageUrl", imageUrl)
                    .toString();
            String raw = tool.call(jsonArgs);
            return parse(raw);
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Wardrobe photo analysis MCP call failed, tool={}, type={}", toolName,
                    exception.getClass().getSimpleName(), exception);
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
        }
    }

    @Override
    public String providerName() { return "mcp"; }

    @Override
    public String promptVersion() { return properties.getMcp().getPromptVersion(); }

    /**
     * Parses the MCP result JSON into candidate drafts.
     *
     * <p>Applies the same business rules as {@link FashionVisionCandidateAnalyzer#parse}:
     * category normalization, quality threshold enforcement, and display-name generation
     * via {@link FashionItemNamer}.</p>
     */
    WardrobePhotoAnalyzer.AnalysisResult parse(String raw) {
        try {
            JsonNode root = McpToolSupport.objectMapper().readTree(McpToolSupport.extractJson(raw));
            JsonNode candidates = root != null && root.isArray() ? root : root.path("candidates");
            if (!candidates.isArray()) {
                return new WardrobePhotoAnalyzer.AnalysisResult("Unable to read structured clothing candidates", List.of());
            }
            List<ClothingCandidateDraft> drafts = new ArrayList<>();
            int index = 0;
            for (JsonNode node : candidates) {
                if (!node.isObject() || drafts.size() >= 8) continue;
                drafts.add(draft(index++, node));
            }
            String summary = root != null && root.isObject() ? text(root.path("summary").asText(), 512) : "";
            return new WardrobePhotoAnalyzer.AnalysisResult(summary, List.copyOf(drafts));
        } catch (JsonProcessingException exception) {
            return new WardrobePhotoAnalyzer.AnalysisResult("Vision response was not valid structured JSON", List.of());
        }
    }

    private ClothingCandidateDraft draft(int index, JsonNode node) throws JsonProcessingException {
        BigDecimal confidence = score(node.path("confidence"));
        BigDecimal quality = score(node.path("qualityScore"));
        ClothingCompletenessStatus completeness = completeness(node.path("completenessStatus").asText());
        String category = category(node.path("categoryCode").asText());
        String guidance = text(node.path("retakeGuidance").asText(), 512);
        if (category.equals("UNKNOWN") || quality.compareTo(MINIMUM_USABLE_QUALITY) < 0) {
            completeness = ClothingCompletenessStatus.RETAKE_REQUIRED;
            if (guidance.isBlank()) guidance = "请补拍这件衣物的主要轮廓，确保类别、颜色和大部分形状清晰可见。";
        }
        JsonNode attributes = node.path("attributes");
        String attributesJson = attributes.isObject() ? McpToolSupport.objectMapper().writeValueAsString(attributes) : "{}";
        String rawName = text(node.path("displayName").asText(), 128);
        String fit = text(node.path("fitCode").asText(), 64);
        String pattern = attributes.path("patternCode").asText();
        String color = text(node.path("colorPrimary").asText(), 64);
        String displayName = FashionItemNamer.nameFor(category, color, rawName + " " + attributes, fit, pattern);
        return new ClothingCandidateDraft(index, displayName, category, color, strings(node.path("secondaryColors")),
                strings(node.path("styleTags")), fit, strings(node.path("seasonTags")),
                attributesJson, confidence, quality, completeness, guidance);
    }

    // --- 以下工具方法与 FashionVisionCandidateAnalyzer 中的实现保持一致 ---
    // 业务规则必须跨 provider 统一：类目归一化、质量阈值、文本截断

    private static ClothingCompletenessStatus completeness(String value) {
        try {
            return ClothingCompletenessStatus.valueOf(text(value, 32).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ClothingCompletenessStatus.RETAKE_REQUIRED;
        }
    }

    private static String category(String value) {
        return switch (text(value, 64).toUpperCase(Locale.ROOT)) {
            case "T_SHIRT", "TEE", "TOP", "T恤", "短袖", "上衣" -> "T_SHIRT";
            case "SHIRT", "衬衫" -> "SHIRT";
            case "KNITWEAR", "针织衫", "毛衣" -> "KNITWEAR";
            case "JACKET", "OUTERWEAR", "夹克", "外套" -> "JACKET";
            case "JEANS", "牛仔裤" -> "JEANS";
            case "STRAIGHT_PANTS", "BOTTOM", "PANTS", "TROUSERS", "直筒裤", "裤子", "长裤" -> "STRAIGHT_PANTS";
            case "SKIRT", "半身裙", "裙子" -> "SKIRT";
            case "DRESS", "连衣裙" -> "DRESS";
            case "SHOES", "鞋", "鞋子" -> "SHOES";
            case "BAG", "包", "包袋" -> "BAG";
            case "ACCESSORY", "配饰" -> "ACCESSORY";
            default -> "UNKNOWN";
        };
    }

    private static BigDecimal score(JsonNode node) {
        if (!node.isNumber()) return BigDecimal.ZERO;
        BigDecimal value = node.decimalValue();
        return value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ZERO : value;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) values.add(text(value.asText(), 128)); });
        return List.copyOf(values);
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
