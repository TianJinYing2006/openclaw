package com.wechatbot.fashion.tianxing;

/**
 * BFR 体脂率计算结果，不暴露第三方接口的原始 JSON。
 */
public record BfrInfo(
        String bfr,              // 当前体脂率，如 "14%"
        String tip,              // 身材小贴士
        String healthy,          // 健康风险，如 "风险较低"
        String normalBfrRange,   // 正常体脂率范围，如 "14%~20%"
        String normalWeightRange, // 正常体重范围，如 "59~72"
        String idealWeight       // 标准体重，如 "65"
) {
    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("📊 体脂率计算结果\n");
        sb.append("当前体脂率：").append(bfr != null ? bfr : "未知").append('\n');
        if (normalBfrRange != null) {
            sb.append("正常范围：").append(normalBfrRange).append('\n');
        }
        if (healthy != null) {
            sb.append("健康风险：").append(healthy).append('\n');
        }
        if (normalWeightRange != null) {
            sb.append("正常体重范围：").append(normalWeightRange).append(" kg\n");
        }
        if (idealWeight != null) {
            sb.append("标准体重：").append(idealWeight).append(" kg\n");
        }
        if (tip != null && !tip.isBlank()) {
            sb.append("💡 ").append(tip);
        }
        return sb.toString();
    }
}
