package com.example.ykdsummer.ai.fashion.model;

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
            String scene = "daily";
            String lower = userInput.toLowerCase();
            if (lower.contains("婚礼") || lower.contains("结婚")) scene = "wedding";
            else if (lower.contains("约会") || lower.contains("相亲")) scene = "date";
            else if (lower.contains("上班") || lower.contains("通勤") || lower.contains("工作")) scene = "work";
            else if (lower.contains("海边") || lower.contains("海滩") || lower.contains("游泳")) scene = "beach";
            else if (lower.contains("运动") || lower.contains("健身") || lower.contains("跑步")) scene = "sport";
            else if (lower.contains("旅行") || lower.contains("旅游")) scene = "travel";

            String season = "summer";
            if (lower.contains("冬天") || lower.contains("冬季")) season = "winter";
            else if (lower.contains("春天") || lower.contains("春季")) season = "spring";
            else if (lower.contains("秋天") || lower.contains("秋季")) season = "autumn";

            int formality = 2;
            if ("wedding".equals(scene)) formality = 4;
            else if ("work".equals(scene)) formality = 3;
            else if ("date".equals(scene)) formality = 3;

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
        return new AnalyzedQuery(
                userInput,
                List.of(userInput),
                QueryParams.fallback(userInput)
        );
    }
}
