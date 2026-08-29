package com.wechatbot.fashion.tianxing;

import java.util.List;

/**
 * 历史的今天事件记录，不暴露第三方接口的原始 JSON。
 */
public record TodayInHistoryInfo(
        String title,
        String eventDate
) {
    /**
     * 格式化输出历史事件列表。
     */
    public static String formatList(String title, List<TodayInHistoryInfo> events) {
        if (events == null || events.isEmpty()) {
            return title + "没有查到历史事件。";
        }
        var sb = new StringBuilder();
        sb.append("📜 ").append(title).append('\n');
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            sb.append(i + 1).append(". ");
            if (e.eventDate() != null && !e.eventDate().isBlank()) {
                sb.append('(').append(e.eventDate().substring(0, 4)).append(") ");
            }
            sb.append(e.title()).append('\n');
        }
        return sb.toString();
    }
}
