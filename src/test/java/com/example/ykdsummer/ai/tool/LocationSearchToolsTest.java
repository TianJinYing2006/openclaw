package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.location.LocationSearchService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LocationSearchToolsTest {

    @Test
    void returnsConfigurationMessageWithoutCallingTencentMaps() {
        LocationSearchService service = mock(LocationSearchService.class);
        when(service.isConfigured()).thenReturn(false);
        LocationSearchTools tools = new LocationSearchTools(service);

        assertThat(tools.searchPoi("餐厅", "杭州")).isEqualTo("腾讯地图 POI 搜索未配置 API Key");
        assertThat(tools.searchNearbyPoi("医院", 30.2, 120.1, 1000)).isEqualTo("腾讯地图 POI 搜索未配置 API Key");
        assertThat(tools.geocode("西湖")).isEqualTo("腾讯地图 POI 搜索未配置 API Key");

        verify(service, times(3)).isConfigured();
        verifyNoMoreInteractions(service);
    }
}
