package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.StockInfo;
import com.example.ykdsummer.tianxing.StockService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 暴露给模型的股票行情工具边界。
 */
@Component
public class StockTools implements AiTool {

    private final StockService stockService;

    public StockTools(StockService stockService) {
        this.stockService = stockService;
    }

    @Tool(
            name = "get_stock_quotes",
            description = "查询A股、港股、美股等股票实时行情，包括最新价、涨跌幅、最高最低价、成交量等。"
                    + "港股代码加 hk 前缀，如 hk00700（腾讯）；沪市加 sh 前缀，如 sh000001（上证指数）；"
                    + "深市加 sz 前缀，如 sz000001（平安银行）。最多同时查询10只。"
    )
    public String getStockQuotes(
            @ToolParam(
                    required = true,
                    description = "股票代码列表，例如 [\"hk00700\",\"sh000001\"]，每个代码需带交易所前缀"
            )
            List<String> codes
    ) {
        List<StockInfo> stocks = stockService.queryStocks(codes);
        return StockInfo.formatList(stocks);
    }
}
