package com.example.ykdsummer.ai.fashion.look.model;

import java.util.List;

/**
 * QueryAnalyzer 的输出：将用户模糊需求分解为结构化参数。
 *
 * @param originalQuery     用户原始问题
 * @param decomposedQueries 拆分后的子查询列表，用于 RAG 检索
 * @param params            结构化参数
 */
public record AnalyzedQuery(String originalQuery, List<String> decomposedQueries, QueryParams params) {

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
        return new AnalyzedQuery(userInput, List.copyOf(queries), params);
    }
}
