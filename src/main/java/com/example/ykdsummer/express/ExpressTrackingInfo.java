package com.example.ykdsummer.express;

import java.util.List;

/**
 * 快递物流跟踪信息，从快递鸟 API 解析后的稳定领域对象。
 */
public record ExpressTrackingInfo(
        String shipperCode,
        String shipperName,
        String logisticCode,
        int state,
        boolean success,
        String reason,
        List<Trace> traces
) {

    /** 物流状态中文描述 */
    public String stateText() {
        return switch (state) {
            case 0 -> "暂无轨迹信息";
            case 1 -> "已揽收";
            case 2 -> "运输中";
            case 3 -> "已签收";
            case 4 -> "退件/异常";
            default -> "未知状态";
        };
    }

    public record Trace(
            String acceptTime,
            String acceptStation,
            String location
    ) {}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 ").append(shipperName).append(" · ").append(logisticCode).append("\n");
        sb.append("状态：").append(stateText()).append("\n");
        if (traces != null && !traces.isEmpty()) {
            sb.append("--- 最新轨迹 ---\n");
            int from = Math.max(0, traces.size() - 3);
            for (int i = from; i < traces.size(); i++) {
                Trace t = traces.get(i);
                sb.append("▸ ").append(t.acceptTime()).append("  ");
                if (t.location() != null && !t.location().isBlank()) {
                    sb.append("[").append(t.location()).append("] ");
                }
                sb.append(t.acceptStation()).append("\n");
            }
        }
        return sb.toString();
    }
}
