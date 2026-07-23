package com.example.ykdsummer.bilibili;

import java.util.List;

/**
 * B 站直播间信息，从 uapis.cn API 解析后的稳定领域对象。
 */
public record LiveRoomInfo(
        long uid,
        long roomId,
        long shortId,
        long attention,
        long online,
        boolean portrait,
        int liveStatus,
        String title,
        String areaName,
        String parentAreaName,
        String description,
        String tags,
        String liveTime,
        List<String> hotWords
) {

    /** 直播状态中文描述 */
    public String liveStatusText() {
        return switch (liveStatus) {
            case 1 -> "直播中";
            case 2 -> "轮播中";
            default -> "未开播";
        };
    }

    /** 是否是竖屏直播 */
    public String portraitText() {
        return portrait ? "竖屏" : "横屏";
    }

    /** 格式化在线人数 */
    public String onlineText() {
        if (online >= 10_000) {
            return String.format("%.1f万", online / 10_000.0);
        }
        return String.valueOf(online);
    }

    /** 格式化粉丝数 */
    public String attentionText() {
        if (attention >= 10_000) {
            return String.format("%.1f万", attention / 10_000.0);
        }
        return String.valueOf(attention);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("B 站直播间\n");
        sb.append("标题：").append(title).append("\n");
        sb.append("状态：").append(liveStatusText());
        if (liveStatus == 1 && liveTime != null && !liveTime.isBlank()) {
            sb.append("（开播于 ").append(liveTime).append("）");
        }
        sb.append("\n");
        sb.append("在线：").append(onlineText()).append("  粉丝：").append(attentionText()).append("\n");
        sb.append("分区：").append(parentAreaName).append(" - ").append(areaName).append("\n");
        sb.append("画面：").append(portraitText()).append("\n");
        if (tags != null && !tags.isBlank()) {
            sb.append("标签：").append(tags).append("\n");
        }
        sb.append("房间号：").append(roomId);
        if (shortId > 0) {
            sb.append("（短号 ").append(shortId).append("）");
        }
        sb.append("\n");
        if (description != null && !description.isBlank()) {
            sb.append("简介：").append(description).append("\n");
        }
        sb.append("主播 UID：").append(uid);
        return sb.toString();
    }
}
