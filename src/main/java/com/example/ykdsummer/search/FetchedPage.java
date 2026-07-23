package com.example.ykdsummer.search;

/**
 * 网页抓取结果。
 */
public record FetchedPage(
        String url,
        String title,
        String content
) {
    @Override
    public String toString() {
        String truncated = content.length() > 3000
                ? content.substring(0, 3000) + "...（已截断）"
                : content;
        return String.format("""
                标题：%s
                来源：%s

                %s""", title, url, truncated);
    }
}
