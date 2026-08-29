package com.wechatbot.fashion.ai.fashion.look.model;

import java.util.List;

/**
 * QueryAnalyzer 的输出：将用户模糊需求分解为结构化参数。
 *
 * @param originalQuery        用户原始问题
 * @param decomposedQueries    拆分后的子查询列表，用于 RAG 检索
 * @param params               结构化参数
 * @param hypotheticalOutfit   HyDE 假设穿搭描述：LLM 基于用户需求生成一段"想象中
 *                             最匹配的 outfit 文本"（含场合/季节/风格/单品建议），
 *                             用作 RAGFlow 语义检索词。null/blank 时回退到
 *                             originalQuery + params 拼接的旧检索词构造。
 *                             <p>持久缓存（{@code fashion_query_analysis_cache}）
 *                             旧记录反序列化时该字段为 null，自然回退旧逻辑，
 *                             实现 HyDE 的渐进式启用，不破坏存量缓存。
 */
public record AnalyzedQuery(String originalQuery, List<String> decomposedQueries,
                            QueryParams params, String hypotheticalOutfit) {

    /** 兼容旧调用方（无 HyDE 描述）的工厂方法。 */
    public AnalyzedQuery(String originalQuery, List<String> decomposedQueries, QueryParams params) {
        this(originalQuery, decomposedQueries, params, null);
    }

    /**
     * 穿搭需求的结构化参数。
     *
     * @param scene     场景：wedding/date/work/sport/beach/daily 等
     * @param season    季节：spring/summer/autumn/winter
     * @param formality 正式度 1-5
     * @param gender    性别：male/female/unknown
     * @param styleHint 风格提示：优雅/休闲/街头/浪漫 等
     */
    public record QueryParams(String scene, String season, int formality, String gender, String styleHint) {

        /** 关键词匹配兜底时使用的默认参数。 */
        public static QueryParams fallback(String userInput) {
            String scene = "DAILY";
            String lower = userInput.toLowerCase();
            if (lower.contains("婚礼") || lower.contains("结婚") || lower.contains("晚宴") || lower.contains("正式")) {
                scene = "FORMAL_EVENT";
            } else if (lower.contains("上班") || lower.contains("通勤") || lower.contains("工作") || lower.contains("开会")) {
                scene = "WORKPLACE";
            } else if (lower.contains("上学") || lower.contains("校园") || lower.contains("学生")) {
                scene = "SCHOOL";
            } else if (lower.contains("海边") || lower.contains("海滩") || lower.contains("度假") || lower.contains("游泳")) {
                scene = "OUTDOOR";
            } else if (lower.contains("运动") || lower.contains("健身") || lower.contains("跑步") || lower.contains("爬山")) {
                scene = "OUTDOOR";
            } else if (lower.contains("旅行") || lower.contains("旅游") || lower.contains("出差")) {
                scene = "TRAVEL";
            }

            String season = "SUMMER";
            if (lower.contains("冬天") || lower.contains("冬季")) season = "WINTER";
            else if (lower.contains("春天") || lower.contains("春季")) season = "SPRING";
            else if (lower.contains("秋天") || lower.contains("秋季")) season = "AUTUMN";

            int formality = 2;
            if ("FORMAL_EVENT".equals(scene)) formality = 4;
            else if ("WORKPLACE".equals(scene)) formality = 3;

            return new QueryParams(scene, season, formality, "unknown", "");
        }

        public String toParamString() {
            return "场景=" + scene + ", 季节=" + season + ", 正式度=" + formality + "/5"
                    + (gender != null && !"unknown".equals(gender) ? ", 性别=" + gender : "")
                    + (styleHint != null && !styleHint.isBlank() ? ", 风格=" + styleHint : "");
        }
    }

    /** 关键词兜底分析，当 LLM 调用失败时使用。 */
    public static AnalyzedQuery fallback(String userInput) {
        QueryParams params = QueryParams.fallback(userInput);
        // 中英混合子查询：原始输入 + 场景/季节枚举，保证枚举 token 可命中
        List<String> queries = new java.util.ArrayList<>();
        queries.add(userInput);
        if (params.scene() != null && !params.scene().isBlank()) {
            queries.add(params.scene());
        }
        if (params.season() != null && !params.season().isBlank()) {
            queries.add(params.season());
        }
        // 兜底 HyDE：根据识别的场景/季节/正式度拼一段假设描述，比单纯拼接枚举更接近
        // outfit 文档里的"优雅/正式度4"等表达，给向量检索额外信号
        String hyde = buildFallbackHyde(userInput, params);
        return new AnalyzedQuery(userInput, List.copyOf(queries), params, hyde);
    }

    /**
     * 关键词兜底时生成一段 HyDE 假设描述。
     *
     * <p>用场景→场合中文 + 季节中文 + 正式度档位词拼成一句自然语言，
     * 让 RAGFlow 向量检索有更丰富的语义锚点，而不是只靠"WORKPLACE / SUMMER"
     * 这种英文枚举 token。
     */
    private static String buildFallbackHyde(String userInput, QueryParams params) {
        StringBuilder sb = new StringBuilder();
        if (userInput != null && !userInput.isBlank()) {
            sb.append(userInput);
        }
        sb.append(" 适合场合：").append(sceneToCn(params.scene()));
        if (params.season() != null && !"UNKNOWN".equalsIgnoreCase(params.season())
                && !params.season().isBlank()) {
            sb.append(" 季节：").append(seasonToCn(params.season()));
        }
        sb.append(" 风格：").append(params.styleHint() == null || params.styleHint().isBlank()
                ? formalityToStyle(params.formality()) : params.styleHint());
        sb.append(" 正式度：").append(params.formality()).append("/5");
        return sb.toString();
    }

    private static String sceneToCn(String scene) {
        return switch (scene == null ? "" : scene) {
            case "FORMAL_EVENT" -> "婚礼/正式晚宴";
            case "WORKPLACE", "COMMUTE" -> "通勤/办公";
            case "SCHOOL" -> "校园/上学";
            case "TRAVEL" -> "旅行/出差";
            case "OUTDOOR" -> "户外/运动/海边";
            default -> "日常/休闲";
        };
    }

    private static String seasonToCn(String season) {
        return switch (season == null ? "" : season) {
            case "SPRING" -> "春季";
            case "SUMMER" -> "夏季";
            case "AUTUMN" -> "秋季";
            case "WINTER" -> "冬季";
            default -> "四季皆可";
        };
    }

    private static String formalityToStyle(int formality) {
        if (formality >= 4) return "优雅/正式";
        if (formality == 3) return "通勤/简约";
        return "休闲/舒适";
    }
}
