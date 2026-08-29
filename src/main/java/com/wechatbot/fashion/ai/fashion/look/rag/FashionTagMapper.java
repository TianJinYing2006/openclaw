package com.wechatbot.fashion.ai.fashion.look.rag;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 穿搭标签映射工具：将 LLM 输出的场景/风格/季节与种子数据中的英文枚举对齐。
 *
 * <p>QueryAnalyzer 输出的 scene（如 work/wedding）与检索表内 scene 列的
 * 枚举值（如 WORKPLACE/COMMUTE/FORMAL_EVENT）不一致，styleHint 为中文、
 * style 列为英文。检索前必须先做映射，否则场景过滤与加权匹配全部失效。
 */
public final class FashionTagMapper {

    /** LLM 场景 → 种子数据 scene 枚举（与 data/xiaohongshu_fashion_seed.json 对齐）。 */
    private static final Map<String, List<String>> SCENE_MAP = Map.ofEntries(
            Map.entry("work", List.of("WORKPLACE", "COMMUTE")),
            Map.entry("commute", List.of("COMMUTE", "WORKPLACE")),
            Map.entry("business", List.of("BUSINESS_MEETING", "WORKPLACE")),
            Map.entry("formal", List.of("FORMAL_EVENT")),
            Map.entry("wedding", List.of("FORMAL_EVENT")),
            Map.entry("school", List.of("SCHOOL")),
            Map.entry("travel", List.of("TRAVEL")),
            Map.entry("trip", List.of("TRAVEL")),
            Map.entry("sport", List.of("OUTDOOR")),
            Map.entry("outdoor", List.of("OUTDOOR")),
            Map.entry("beach", List.of("OUTDOOR", "DAILY")),
            Map.entry("daily", List.of("DAILY"))
    );

    /** 中文/英文风格 → 种子数据 style 枚举。 */
    private static final Map<String, List<String>> STYLE_MAP = Map.ofEntries(
            Map.entry("优雅", List.of("ELEGANT")),
            Map.entry("elegant", List.of("ELEGANT")),
            Map.entry("休闲", List.of("CASUAL")),
            Map.entry("casual", List.of("CASUAL")),
            Map.entry("街头", List.of("STREET")),
            Map.entry("street", List.of("STREET")),
            Map.entry("浪漫", List.of("ROMANTIC")),
            Map.entry("romantic", List.of("ROMANTIC")),
            Map.entry("甜美", List.of("SWEET")),
            Map.entry("sweet", List.of("SWEET")),
            Map.entry("商务", List.of("BUSINESS", "COMMUTER")),
            Map.entry("business", List.of("BUSINESS", "COMMUTER")),
            Map.entry("通勤", List.of("COMMUTER", "BUSINESS")),
            Map.entry("复古", List.of("VINTAGE")),
            Map.entry("vintage", List.of("VINTAGE")),
            Map.entry("学院", List.of("PREPPY")),
            Map.entry("preppy", List.of("PREPPY")),
            Map.entry("极简", List.of("MINIMAL")),
            Map.entry("minimal", List.of("MINIMAL")),
            Map.entry("性感", List.of("SEXY")),
            Map.entry("sexy", List.of("SEXY")),
            Map.entry("波西米亚", List.of("BOHEMIAN")),
            Map.entry("bohemian", List.of("BOHEMIAN")),
            Map.entry("户外", List.of("OUTDOOR")),
            Map.entry("正式", List.of("FORMAL", "BUSINESS")),
            Map.entry("formal", List.of("FORMAL"))
    );

    /** 中英文季节 → 种子数据 season 枚举。 */
    private static final Map<String, List<String>> SEASON_MAP = Map.ofEntries(
            Map.entry("spring", List.of("SPRING")),
            Map.entry("summer", List.of("SUMMER")),
            Map.entry("autumn", List.of("AUTUMN")),
            Map.entry("fall", List.of("AUTUMN")),
            Map.entry("winter", List.of("WINTER")),
            Map.entry("春天", List.of("SPRING")),
            Map.entry("春季", List.of("SPRING")),
            Map.entry("夏天", List.of("SUMMER")),
            Map.entry("夏季", List.of("SUMMER")),
            Map.entry("秋天", List.of("AUTUMN")),
            Map.entry("秋季", List.of("AUTUMN")),
            Map.entry("冬天", List.of("WINTER")),
            Map.entry("冬季", List.of("WINTER"))
    );

    private FashionTagMapper() {
    }

    /**
     * 将 LLM 场景映射为种子数据 scene 枚举列表。
     *
     * @param scene LLM 输出的场景（work/wedding/travel 等），可为 null
     * @return 对应的数据枚举，无匹配时返回空列表
     */
    public static List<String> mapScene(String scene) {
        return lookup(SCENE_MAP, scene);
    }

    /**
     * 将风格提示（中文或英文）映射为种子数据 style 枚举列表。
     *
     * @param styleHint 风格提示（如"优雅休闲"、ELEGANT），可为 null
     * @return 对应的数据枚举，无匹配时返回空列表
     */
    public static List<String> mapStyle(String styleHint) {
        if (styleHint == null || styleHint.isBlank()) {
            return List.of();
        }
        // 英文直匹配
        List<String> exact = lookup(STYLE_MAP, styleHint);
        if (!exact.isEmpty()) {
            return exact;
        }
        // 中文可能包含多个风格词（如"优雅休闲"），逐个包含匹配
        for (Map.Entry<String, List<String>> e : STYLE_MAP.entrySet()) {
            if (e.getKey().length() >= 2 && styleHint.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return List.of();
    }

    /**
     * 将季节（中英文）映射为种子数据 season 枚举列表。
     *
     * @param season 季节（spring/summer/冬天 等），可为 null
     * @return 对应的数据枚举，无匹配时返回空列表
     */
    public static List<String> mapSeason(String season) {
        return lookup(SEASON_MAP, season);
    }

    private static List<String> lookup(Map<String, List<String>> map, String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        List<String> value = map.get(key.trim().toLowerCase(Locale.ROOT));
        return value == null ? List.of() : value;
    }
}
