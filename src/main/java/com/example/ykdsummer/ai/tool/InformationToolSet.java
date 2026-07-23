package com.example.ykdsummer.ai.tool;

import org.springframework.stereotype.Component;

/**
 * 汇总外部只读信息查询 Tool，避免让旧分支的网关覆盖已验证的微信资产回传链路。
 */
@Component
public class InformationToolSet {

    private final Object[] toolBeans;

    public InformationToolSet(
            BfrTools bfrTools,
            BilibiliTools bilibiliTools,
            ExchangeRateTools exchangeRateTools,
            ExpressTools expressTools,
            HoroscopeTools horoscopeTools,
            IPTools ipTools,
            RecipeTools recipeTools,
            ScenicSpotTools scenicSpotTools,
            StockTools stockTools,
            TodayInHistoryTools todayInHistoryTools,
            WebPageFetchTools webPageFetchTools
    ) {
        this.toolBeans = new Object[]{
                bfrTools,
                bilibiliTools,
                exchangeRateTools,
                expressTools,
                horoscopeTools,
                ipTools,
                recipeTools,
                scenicSpotTools,
                stockTools,
                todayInHistoryTools,
                webPageFetchTools
        };
    }

    public Object[] toolBeans() {
        return toolBeans.clone();
    }
}
