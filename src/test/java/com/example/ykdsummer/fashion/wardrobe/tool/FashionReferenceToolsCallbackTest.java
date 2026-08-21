package com.example.ykdsummer.fashion.wardrobe.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.wardrobe.FashionReferenceFixtures;
import com.example.ykdsummer.fashion.wardrobe.application.FashionReferenceSemanticSearchService;
import com.example.ykdsummer.fashion.wardrobe.application.FashionReferenceService;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;

class FashionReferenceToolsCallbackTest {
    @Test
    void searchesOnlyTheExplicitPublicReferenceServiceAndAttachesImages() {
        FashionReferenceService service = mock(FashionReferenceService.class);
        @SuppressWarnings("unchecked") ObjectProvider<FashionReferenceSemanticSearchService> semantic = mock(ObjectProvider.class);
        when(semantic.getIfAvailable()).thenReturn(null);
        FashionReferenceLook look = FashionReferenceFixtures.look();
        when(service.search(any(), eq(4))).thenReturn(List.of(look));
        when(service.image(look)).thenReturn(java.util.Optional.of(new byte[]{1, 2, 3}));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("wechat-user");
        FashionReferenceTools tools = new FashionReferenceTools(service, semantic, artifacts, AiTraceLogger.disabled());

        String result = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(value -> value.getToolDefinition().name().equals("search_fashion_references"))
                .findFirst().orElseThrow().call("""
                        {"categoryCode":"T恤","color":"浅灰色","styleTags":["简约"],"limit":4}
                        """);

        assertThat(result).contains("公共穿搭参考候选", "浅灰色宽松短袖T恤", "已附带 1 张参考图")
                .doesNotContain("referenceLookId", "img_public");
        assertThat(artifacts.finish()).hasSize(1);
        ArgumentCaptor<WardrobeSearchCriteria> criteria = ArgumentCaptor.forClass(WardrobeSearchCriteria.class);
        verify(service).search(criteria.capture(), eq(4));
        assertThat(criteria.getValue().categoryCodes()).contains("T_SHIRT");
        assertThat(criteria.getValue().styleTags()).contains("MINIMAL");
    }
}
