package com.example.ykdsummer.tianxing;

import java.util.List;

/**
 * 股票行情数据。
 */
public record StockInfo(
        String code,
        String name,
        double price,
        double change,
        double changePercent,
        double high,
        double low,
        double open,
        double prevClose,
        double avgPrice,
        String volume,
        String amount,
        double pe,
        String marketCap,
        String turnoverRate
) {
    /**
     * 从 API 返回的逗号分隔字符串解析单只股票行情。
     *
     * @param code    股票代码
     * @param rawData 逗号分隔的行情数据
     * @return StockInfo
     */
    public static StockInfo fromApiData(String code, String rawData) {
        String[] parts = rawData.split(",");
        return new StockInfo(
                code,
                safeGet(parts, 1),            // 中文名称
                parseDouble(safeGet(parts, 2)),   // 最新价
                parseDouble(safeGet(parts, 3)),   // 涨跌额
                parsePercent(safeGet(parts, 4)),  // 涨跌幅
                parseDouble(safeGet(parts, 5)),   // 最高
                parseDouble(safeGet(parts, 6)),   // 最低
                parseDouble(safeGet(parts, 7)),   // 开盘
                parseDouble(safeGet(parts, 8)),   // 昨收
                parseDouble(safeGet(parts, 9)),   // 均价
                safeGet(parts, 10),                // 成交量
                safeGet(parts, 11),                // 成交额
                parseDouble(safeGet(parts, 12)),   // 市盈率
                safeGet(parts, 13),                // 总市值
                safeGet(parts, 14)                 // 换手率
        );
    }

    /**
     * 批量格式化输出。
     */
    public static String formatList(List<StockInfo> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return "没有查到相关股票行情。";
        }
        var sb = new StringBuilder("📈 股票行情\n");
        for (var s : stocks) {
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(name).append(" (").append(code).append(")\n");
        sb.append(String.format("最新价：%.2f", price));
        if (change >= 0) {
            sb.append(String.format("   ↑ +%.2f (+%.2f%%)", change, changePercent));
        } else {
            sb.append(String.format("   ↓ %.2f (%.2f%%)", change, changePercent));
        }
        sb.append('\n');
        sb.append(String.format("最高：%.2f    最低：%.2f", high, low)).append('\n');
        sb.append(String.format("开盘：%.2f    昨收：%.2f", open, prevClose)).append('\n');
        sb.append("成交量：").append(volume).append("    成交额：").append(amount).append('\n');
        if (pe > 0) sb.append("市盈率：").append(pe).append('\n');
        return sb.toString();
    }

    private static String safeGet(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }

    private static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static double parsePercent(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.replace("%", ""));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
