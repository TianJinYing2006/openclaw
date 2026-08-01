package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.exchange.ExchangeRateInfo;
import com.example.ykdsummer.exchange.ExchangeRateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的汇率转换工具边界。
 */
@Component
public class ExchangeRateTools implements AiTool {

    private final ExchangeRateService exchangeRateService;
    private final RealtimeSearchFallback realtimeSearchFallback;

    public ExchangeRateTools(ExchangeRateService exchangeRateService) {
        this(exchangeRateService, RealtimeSearchFallback.unavailable());
    }

    @Autowired
    public ExchangeRateTools(
            ExchangeRateService exchangeRateService, RealtimeSearchFallback realtimeSearchFallback
    ) {
        this.exchangeRateService = exchangeRateService;
        this.realtimeSearchFallback = realtimeSearchFallback;
    }

    @Tool(
            name = "convert_currency",
            description = "货币汇率转换，将指定金额从一种货币转换为另一种货币。"
                    + "支持美元(USD)、人民币(CNY)、欧元(EUR)、日元(JPY)、英镑(GBP)、港币(HKD)等160+种货币。"
                    + "使用三位ISO 4217货币代码。不指定金额时默认转换1单位。"
    )
    public Object convertCurrency(
            @ToolParam(
                    required = true,
                    description = "源货币的三位ISO代码，例如 USD、CNY、EUR、JPY、GBP"
            )
            String base,
            @ToolParam(
                    required = true,
                    description = "目标货币的三位ISO代码，例如 USD、CNY、EUR、JPY、GBP"
            )
            String target,
            @ToolParam(
                    required = false,
                    description = "要转换的金额，默认1.0"
            )
            Double amount
    ) {
        double amt = (amount == null || amount <= 0) ? 1.0 : amount;
        try {
            return exchangeRateService.convertCurrency(base, target, amt);
        } catch (RuntimeException exception) {
            return realtimeSearchFallback.search(
                    "汇率查询",
                    base + " 兑 " + target + " 今日实时汇率",
                    "数据源暂时不可用"
            );
        }
    }
}
