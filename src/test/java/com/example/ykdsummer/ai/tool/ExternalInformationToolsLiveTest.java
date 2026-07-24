package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.location.LocationSearchInfo;
import com.example.ykdsummer.location.LocationSearchService;
import com.example.ykdsummer.navigation.AmapGeoResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real smoke test for the public-information tools configured only in application-local.properties.
 *
 * <p>The test is deliberately opt-in so normal builds never call third-party services.</p>
 */
@SpringBootTest(
        properties = "ilink.enabled=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "EXTERNAL_TOOLS_LIVE_TEST", matches = "true")
class ExternalInformationToolsLiveTest {

    @Autowired
    private BochaWebSearchTools bochaWebSearchTools;

    @Autowired
    private LocationSearchService locationSearchService;

    @Autowired
    private AmapTools amapTools;

    @Test
    @Timeout(120)
    void bochaSearchReturnsUsableResults() {
        String webSearch = bochaWebSearchTools.searchWeb("OpenAI");
        assertThat(webSearch).startsWith("博查搜索结果：").contains("来源：");
    }

    @Test
    @Timeout(60)
    void tencentPlaceSearchReturnsUsableResults() {
        LocationSearchInfo places = locationSearchService.search("北京大学", "北京");
        assertThat(places.count()).isPositive();
        assertThat(places.pois()).isNotEmpty();
    }

    @Test
    @Timeout(90)
    void amapGeocodingAndV5RoutesReturnUsableResults() {
        AmapGeoResult origin = amapTools.geoEncode("北京天安门", "北京");
        AmapGeoResult destination = amapTools.geoEncode("北京故宫", "北京");
        assertThat(origin.status()).isEqualTo("1");
        assertThat(destination.status()).isEqualTo("1");
        assertThat(origin.geocodes()).isNotEmpty();
        assertThat(destination.geocodes()).isNotEmpty();

        String route = amapTools.routePlan(
                origin.geocodes().getFirst().location(),
                destination.geocodes().getFirst().location(),
                "driving",
                "北京故宫",
                "010",
                "010"
        );
        assertThat(route).contains("驾车：", "步行：", "导航");
    }
}
