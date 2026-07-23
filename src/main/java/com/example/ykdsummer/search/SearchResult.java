package com.example.ykdsummer.search;

import java.util.List;

/**
 * 联网搜索结果。
 */
public record SearchResult(
        String title,
        String url,
        String description
) {
    public static String formatList(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "没有找到相关结果。";
        }
        var sb = new StringBuilder("🔍 搜索结果\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append(String.format("%d. %s\n   %s\n   %s\n",
                    i + 1, r.title(), r.url(), r.description()));
        }
        return sb.toString();
    }
}
