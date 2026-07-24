package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.ykdsummer.bilibili.BilibiliLiveService;
import com.example.ykdsummer.exchange.ExchangeRateService;
import com.example.ykdsummer.iplocation.IPLocationService;
import com.example.ykdsummer.search.WebSearchService;
import com.example.ykdsummer.tianxing.BfrService;
import com.example.ykdsummer.tianxing.HoroscopeService;
import com.example.ykdsummer.tianxing.RecipeService;
import com.example.ykdsummer.tianxing.ScenicSpotService;
import com.example.ykdsummer.tianxing.TodayInHistoryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.client.RestClient;

class InformationToolSetTest {

    @Test
    void exposesEveryIndependentInformationTool() {
        InformationToolSet toolSet = new InformationToolSet(
                new BfrTools(mock(BfrService.class)),
                new BilibiliTools(mock(BilibiliLiveService.class)),
                new ExchangeRateTools(mock(ExchangeRateService.class)),
                new ExpressTools("", HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1")),
                new HoroscopeTools(mock(HoroscopeService.class)),
                new IPTools(mock(IPLocationService.class)),
                new RecipeTools(mock(RecipeService.class)),
                new ScenicSpotTools(mock(ScenicSpotService.class)),
                new TodayInHistoryTools(mock(TodayInHistoryService.class)),
                new WebPageFetchTools(mock(WebSearchService.class))
        );

        assertThat(Arrays.stream(toolSet.toolBeans())
                .flatMap(tool -> Arrays.stream(tool.getClass().getMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name))
                .containsExactlyInAnyOrder(
                        "calculate_bfr",
                        "get_bilibili_live_room_info",
                        "convert_currency",
                        "query_express_tracking",
                        "get_horoscope",
                        "query_ip_location",
                        "search_recipe",
                        "get_recipe_detail",
                        "search_scenic_spot",
                        "query_today_in_history",
                        "fetch_web_page"
                );
    }

    @Test
    void rejectsInvalidExpressNumberBeforeCallingTheNetwork() {
        ExpressTools tool = new ExpressTools("", HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1"));

        assertThat(tool.queryExpressTracking("x", null, null)).isEqualTo("请输入有效的快递单号。");
    }

    @Test
    void rejectsNonHttpPageUrlBeforeFetching() {
        WebPageFetchTools tool = new WebPageFetchTools(mock(WebSearchService.class));

        assertThat(tool.fetchWebPage("file:///C:/private.txt"))
                .isEqualTo("只能抓取带 http:// 或 https:// 的公开网页 URL");
    }
}
