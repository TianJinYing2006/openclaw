package com.example.ykdsummer.exchange;

/**
 * 汇率转换结果，不暴露第三方接口的原始 JSON。
 */
public record ExchangeRateInfo(
        String baseCode,
        String targetCode,
        double conversionRate,
        double conversionResult,
        String lastUpdateUtc
) {
    @Override
    public String toString() {
        return String.format("""
                💱 %s → %s
                汇率：1 %s = %.4f %s
                转换结果：%.2f %s
                更新时间：%s""",
                baseCode, targetCode,
                baseCode, conversionRate, targetCode,
                conversionResult, targetCode,
                lastUpdateUtc != null ? lastUpdateUtc : "未知"
        );
    }
}
