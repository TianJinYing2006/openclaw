package com.example.ykdsummer.ai.fashion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 种子数据条目，对应 data/xiaohongshu_fashion_seed.json 中的一条记录。
 * 同时映射到 MySQL 全文检索表用于检索。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedEntry(
        String id,
        String type,
        String source,
        Outfit outfit,
        Tags tags,
        String summary,
        String likes,
        String url,
        String imagePath
) {

    /** 穿搭单品组合。 */
    public record Outfit(String top, String bottom, String shoes, String accessories) {}

    /** 标签信息。 */
    public record Tags(
            List<String> style,
            List<String> scene,
            List<String> season,
            List<String> bodyType,
            String colorScheme
    ) {}

    /** 拼接所有可搜索文本，用于全文检索索引。 */
    public String searchableText() {
        StringBuilder sb = new StringBuilder();
        if (summary != null) sb.append(summary).append(" ");
        if (outfit != null) {
            if (outfit.top() != null) sb.append(outfit.top()).append(" ");
            if (outfit.bottom() != null) sb.append(outfit.bottom()).append(" ");
            if (outfit.shoes() != null) sb.append(outfit.shoes()).append(" ");
            if (outfit.accessories() != null) sb.append(outfit.accessories()).append(" ");
        }
        if (tags != null) {
            if (tags.style() != null) sb.append(String.join(" ", tags.style())).append(" ");
            if (tags.scene() != null) sb.append(String.join(" ", tags.scene())).append(" ");
            if (tags.season() != null) sb.append(String.join(" ", tags.season())).append(" ");
            if (tags.colorScheme() != null) sb.append(tags.colorScheme()).append(" ");
        }
        return sb.toString().trim();
    }
}
