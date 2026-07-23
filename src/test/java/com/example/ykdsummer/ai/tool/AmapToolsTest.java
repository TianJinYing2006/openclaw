package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.navigation.AmapService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AmapToolsTest {

    @Test
    void returnsFriendlyMessagesWhenTheAmapKeyIsMissing() {
        AmapService amapService = mock(AmapService.class);
        when(amapService.isConfigured()).thenReturn(false);
        AmapTools tools = new AmapTools(amapService);

        assertThat(tools.geoEncode("杭州西湖", "杭州").info()).isEqualTo("高德地图未配置 API Key");
        assertThat(tools.routePlan("120.1,30.2", "120.2,30.3", "walking", "西湖", "0571", "0571"))
                .contains("高德地图未配置 API Key");
        verify(amapService, org.mockito.Mockito.times(2)).isConfigured();
        verifyNoMoreInteractions(amapService);
    }
}
