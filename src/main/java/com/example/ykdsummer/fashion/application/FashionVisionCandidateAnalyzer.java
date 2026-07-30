package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.AiGatewayException;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.ClothingCandidateDraft;
import com.example.ykdsummer.fashion.domain.ClothingCompletenessStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Converts a vision response into structured clothing candidate drafts for a user-confirmed wardrobe. */
@Service
public class FashionVisionCandidateAnalyzer {
    static final String PROMPT_VERSION = "fashion-wardrobe-v2";
    private static final BigDecimal MINIMUM_USABLE_QUALITY = new BigDecimal("0.45");
    private static final String ANALYSIS_PROMPT = """
            You are a garment intake analyst. Inspect the uploaded photo and return JSON only, without Markdown.
            Identify every separately usable clothing item visible in the image. Do not invent hidden details.
            For each candidate, return: displayName, categoryCode, colorPrimary, secondaryColors, styleTags, fitCode,
            seasonTags, attributes, confidence, qualityScore, completenessStatus, retakeGuidance.
            categoryCode must be one of UNKNOWN,T_SHIRT,SHIRT,KNITWEAR,JACKET,JEANS,STRAIGHT_PANTS,SKIRT,DRESS,SHOES,BAG,ACCESSORY.
            completenessStatus must be READY, RETAKE_REQUIRED, or UNSUPPORTED.
            A garment being worn on a person is normal and is NOT a reason to reject it. Use READY when its category,
            main color, major visible silhouette and enough of its shape can be identified to create a conservative wardrobe
            display draft. Small occlusion by hands, bags, hair, another garment, or a top covering a trouser waistband is OK.
            For pants, visible upper thigh/legs through hem is enough even when the waistband is covered by a top. For tops
            and outerwear, tolerate small overlap or a partly hidden hem when the garment remains clearly identifiable.
            The later cutout may infer only a simple, category-consistent continuation for a hidden edge; never invent a logo,
            print, fabric detail, pocket, or decorative feature that is not visible.
            Use RETAKE_REQUIRED only when the selected item is almost entirely hidden, has no recognizable silhouette or
            category, is too blurred/dark, or is so tightly cropped that a conservative draft would be misleading. Explain
            exactly what is missing. attributes is a JSON object and may include patternCode, material, occasionTags,
            visibility, estimatedParts, and notes. confidence and qualityScore are numbers from 0 to 1.
            Root shape: {"summary":"...","candidates":[...]}. Return an empty candidates array when no garment is reliable.
            """;

    private final ImageInspectionService inspection;
    private final ObjectMapper objectMapper;

    public FashionVisionCandidateAnalyzer(ImageInspectionService inspection, ObjectMapper objectMapper) {
        this.inspection = inspection;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(StoredImage source) {
        if (source == null) throw new IllegalArgumentException("source image is required");
        AnalysisResult fromSavedSummary = analyzeSavedSummary(source.tags());
        if (!fromSavedSummary.candidates().isEmpty()) return fromSavedSummary;
        String response = inspection.inspect(source, ANALYSIS_PROMPT);
        if (ImageInspectionService.isTemporaryUnavailableReply(response)) {
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
        }
        return parse(response);
    }

    /**
     * A prior image inspection is already an expensive, OSS-backed multimodal read. Reuse a detailed saved summary
     * for the first wardrobe draft instead of sending the same bytes to a slow upstream a second time. Users still
     * review every candidate and the generated cutout before an item can enter the wardrobe.
     */
    private AnalysisResult analyzeSavedSummary(String tags) {
        String summary = text(tags, 8_000);
        if (summary.length() < 80 || containsAny(summary, "暂时不可用", "无法识别", "未识别")) {
            return new AnalysisResult("", List.of());
        }
        List<ClothingCandidateDraft> drafts = new ArrayList<>();
        addSummaryCandidate(drafts, summary, "T_SHIRT", "上衣", "Polo", "Polo衫", "T恤", "短袖", "上装");
        addSummaryCandidate(drafts, summary, "SHIRT", "衬衫");
        addSummaryCandidate(drafts, summary, "KNITWEAR", "针织衫", "毛衣");
        addSummaryCandidate(drafts, summary, "JACKET", "外套", "夹克", "西装");
        if (containsAny(summary, "牛仔裤", "丹宁裤")) {
            addSummaryCandidate(drafts, summary, "JEANS", "牛仔裤", "丹宁裤");
        } else {
            addSummaryCandidate(drafts, summary, "STRAIGHT_PANTS", "阔腿裤", "直筒裤", "长裤", "裤子", "下装");
        }
        addSummaryCandidate(drafts, summary, "SKIRT", "半身裙", "裙子");
        addSummaryCandidate(drafts, summary, "DRESS", "连衣裙", "裙装");
        addSummaryCandidate(drafts, summary, "SHOES", "鞋子", "厚底鞋", "运动鞋", "皮鞋", "鞋");
        addSummaryCandidate(drafts, summary, "BAG", "包包", "手提包", "背包", "包");
        if (drafts.isEmpty()) return new AnalysisResult("", List.of());
        return new AnalysisResult("已复用这张图片已保存的视觉摘要，生成待确认的服装候选。", List.copyOf(drafts));
    }

    private void addSummaryCandidate(List<ClothingCandidateDraft> drafts, String summary, String category,
                                     String... markers) {
        if (drafts.size() >= 8 || !containsAny(summary, markers)) return;
        String fragment = surroundingText(summary, firstIndex(summary, markers));
        String color = color(fragment);
        String pattern = pattern(fragment);
        String fit = fit(fragment);
        String displayName = FashionItemNamer.nameFor(category, color, fragment, fit, pattern);
        boolean unusable = containsAny(fragment, "完全遮住", "完全被遮", "无法看清", "看不清", "严重模糊", "只露出", "几乎看不见");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("visibility", "derived from saved visual summary");
        String material = material(fragment);
        if (!material.isBlank()) attributes.put("material", material);
        if (!pattern.isBlank()) attributes.put("patternCode", pattern);
        List<String> occasions = occasionTags(fragment);
        if (!occasions.isEmpty()) attributes.put("occasionTags", occasions);
        if (unusable) attributes.put("notes", "Saved summary indicates the item is mostly hidden or incomplete");
        try {
            String attributesJson = objectMapper.writeValueAsString(attributes);
            drafts.add(new ClothingCandidateDraft(drafts.size(), displayName, category, color, List.of(), styleTags(fragment),
                    fit, seasonTags(fragment, category), attributesJson, new BigDecimal("0.72"),
                    unusable ? new BigDecimal("0.35") : new BigDecimal("0.58"),
                    unusable ? ClothingCompletenessStatus.RETAKE_REQUIRED : ClothingCompletenessStatus.READY,
                    unusable ? "请补拍这件衣物的大部分轮廓，确保类别、颜色和主要形状清晰可见。" : ""));
        } catch (JsonProcessingException ignored) {
            // This uses only a fixed in-memory map. If serialization somehow fails, fall through to the vision request.
        }
    }

    private static int firstIndex(String value, String... markers) {
        int earliest = -1;
        for (String marker : markers) {
            int candidate = value.indexOf(marker);
            if (candidate >= 0 && (earliest < 0 || candidate < earliest)) earliest = candidate;
        }
        return earliest < 0 ? 0 : earliest;
    }

    private static String surroundingText(String value, int position) {
        int from = Math.max(Math.max(value.lastIndexOf('\n', position), value.lastIndexOf('；', position)),
                Math.max(value.lastIndexOf('。', position), value.lastIndexOf('*', position))) + 1;
        int to = value.length();
        for (char boundary : new char[] {'\n', '；', '。'}) {
            int candidate = value.indexOf(boundary, position);
            if (candidate >= 0) to = Math.min(to, candidate);
        }
        if (to - from < 8) {
            from = Math.max(0, position - 80);
            to = Math.min(value.length(), position + 240);
        }
        return value.substring(from, to);
    }

    private static boolean containsAny(String value, String... values) {
        if (value == null || value.isBlank()) return false;
        for (String candidate : values) {
            if (candidate != null && !candidate.isBlank() && value.contains(candidate)) return true;
        }
        return false;
    }

    private static String color(String value) {
        if (containsAny(value, "深蓝", "藏青")) return "NAVY";
        if (containsAny(value, "牛仔蓝")) return "DENIM_BLUE";
        if (containsAny(value, "浅灰", "花灰", "灰色")) return "GRAY";
        if (containsAny(value, "黑色", "黑")) return "BLACK";
        if (containsAny(value, "白色", "白")) return "WHITE";
        if (containsAny(value, "米白", "象牙白")) return "OFF_WHITE";
        if (containsAny(value, "卡其")) return "KHAKI";
        if (containsAny(value, "棕", "咖啡")) return "BROWN";
        if (containsAny(value, "蓝色", "蓝")) return "BLUE";
        if (containsAny(value, "红色", "红")) return "RED";
        if (containsAny(value, "绿色", "绿")) return "GREEN";
        if (containsAny(value, "黄色", "黄")) return "YELLOW";
        return "";
    }

    private static List<String> styleTags(String value) {
        List<String> tags = new ArrayList<>();
        if (containsAny(value, "简约", "极简")) tags.add("MINIMAL");
        if (containsAny(value, "休闲", "慵懒")) tags.add("CASUAL");
        if (containsAny(value, "通勤")) tags.add("COMMUTE");
        return List.copyOf(tags);
    }

    private static String fit(String value) {
        if (containsAny(value, "宽松", "Oversize", "阔腿")) return "RELAXED";
        if (containsAny(value, "直筒")) return "STRAIGHT";
        if (containsAny(value, "修身")) return "SLIM";
        return "";
    }

    private static List<String> seasonTags(String value, String category) {
        List<String> seasons = new ArrayList<>();
        if (containsAny(value, "春季", "春天")) seasons.add("SPRING");
        if (containsAny(value, "夏季", "夏天")) seasons.add("SUMMER");
        if (containsAny(value, "秋季", "秋天")) seasons.add("AUTUMN");
        if (containsAny(value, "冬季", "冬天")) seasons.add("WINTER");
        if (seasons.isEmpty() && ("T_SHIRT".equals(category) || containsAny(value, "短袖", "背心"))) seasons.add("SUMMER");
        return List.copyOf(seasons);
    }

    private static String material(String value) {
        if (containsAny(value, "牛仔", "丹宁")) return "DENIM";
        if (containsAny(value, "针织", "毛线", "毛衣")) return "KNIT";
        if (containsAny(value, "棉", "纯棉")) return "COTTON";
        if (containsAny(value, "羊毛")) return "WOOL";
        if (containsAny(value, "皮革", "皮质")) return "LEATHER";
        if (containsAny(value, "亚麻")) return "LINEN";
        return "";
    }

    private static String pattern(String value) {
        if (containsAny(value, "条纹")) return "STRIPED";
        if (containsAny(value, "格纹", "格子")) return "CHECKED";
        if (containsAny(value, "印花", "图案")) return "PRINTED";
        if (containsAny(value, "纯色", "无图案")) return "SOLID";
        return "";
    }

    private static List<String> occasionTags(String value) {
        List<String> tags = new ArrayList<>();
        if (containsAny(value, "休闲", "日常")) tags.add("CASUAL");
        if (containsAny(value, "通勤", "上班")) tags.add("COMMUTE");
        if (containsAny(value, "约会")) tags.add("DATE");
        if (containsAny(value, "正式", "面试")) tags.add("FORMAL");
        if (containsAny(value, "运动")) tags.add("SPORT");
        return List.copyOf(tags);
    }

    AnalysisResult parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            JsonNode candidates = root != null && root.isArray() ? root : root.path("candidates");
            if (!candidates.isArray()) return new AnalysisResult("Unable to read structured clothing candidates", List.of());
            List<ClothingCandidateDraft> drafts = new ArrayList<>();
            int index = 0;
            for (JsonNode node : candidates) {
                if (!node.isObject() || drafts.size() >= 8) continue;
                drafts.add(draft(index++, node));
            }
            String summary = root != null && root.isObject() ? text(root.path("summary").asText(), 512) : "";
            return new AnalysisResult(summary, List.copyOf(drafts));
        } catch (JsonProcessingException exception) {
            return new AnalysisResult("Vision response was not valid structured JSON", List.of());
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
        String attributesJson = attributes.isObject() ? objectMapper.writeValueAsString(attributes) : "{}";
        String rawName = text(node.path("displayName").asText(), 128);
        String fit = text(node.path("fitCode").asText(), 64);
        String pattern = attributes.path("patternCode").asText();
        String color = text(node.path("colorPrimary").asText(), 64);
        String displayName = FashionItemNamer.nameFor(category, color, rawName + " " + attributes, fit, pattern);
        return new ClothingCandidateDraft(index, displayName, category, color, strings(node.path("secondaryColors")),
                strings(node.path("styleTags")), fit, strings(node.path("seasonTags")),
                attributesJson, confidence, quality, completeness, guidance);
    }

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

    private static String extractJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).strip();
        }
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        int start = objectStart < 0 ? arrayStart : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
        if (start < 0) return "{}";
        int objectEnd = value.lastIndexOf('}');
        int arrayEnd = value.lastIndexOf(']');
        int end = Math.max(objectEnd, arrayEnd);
        return end >= start ? value.substring(start, end + 1) : "{}";
    }

    private static String text(String value, int limit) {
        String clean = value == null ? "" : value.replace('\u0000', ' ').strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    public record AnalysisResult(String summary, List<ClothingCandidateDraft> candidates) { }
}
