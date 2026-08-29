package com.wechatbot.fashion.tianxing;

import java.util.List;

/**
 * 旅游景区信息，不暴露第三方接口的原始 JSON。
 */
public record ScenicSpotInfo(
        String name,
        String province,
        String city,
        String description
) {
    /**
     * 格式化输出单个景点。
     */
    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("🏞️ ").append(name);
        if (province != null && !province.isBlank()) {
            sb.append(" · ").append(province);
        }
        if (city != null && !city.isBlank() && !city.equals(province)) {
            sb.append(" · ").append(city);
        }
        if (description != null && !description.isBlank()) {
            sb.append('\n').append(description);
        }
        return sb.toString();
    }

    /**
     * 格式化搜索列表。
     */
    public static String formatList(List<ScenicSpotInfo> items) {
        if (items == null || items.isEmpty()) {
            return "没有找到相关景点。";
        }
        var sb = new StringBuilder("找到以下景点：\n");
        for (int i = 0; i < items.size(); i++) {
            var s = items.get(i);
            sb.append(i + 1).append(". ").append(s.name());
            if (s.city() != null && !s.city().isBlank()) {
                sb.append("（").append(s.city()).append("）");
            }
            sb.append('\n');
        }
        sb.append("\n回复“景点详情 + 编号”查看完整介绍。");
        return sb.toString();
    }
}
