package com.example.ykdsummer.fashion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.fashion.application.FashionCatalogService;
import com.example.ykdsummer.fashion.domain.FashionProduct;
import com.example.ykdsummer.fashion.domain.FashionProductSearch;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionCatalogToolsCallbackTest {

    @Test
    void searchesOnlyPublishedCatalogCandidatesWithStructuredFilters() {
        FashionCatalogService catalog = mock(FashionCatalogService.class);
        when(catalog.searchActiveProducts(any())).thenReturn(List.of(product()));
        FashionCatalogTools tools = new FashionCatalogTools(catalog, AiTraceLogger.disabled());

        String result = callback(tools, "search_fashion_products").call("""
                {"categoryCode":"T恤","styleTag":"简约","occasionTag":"通勤","maxPrice":200,"limit":4}
                """);

        ArgumentCaptor<FashionProductSearch> search = ArgumentCaptor.forClass(FashionProductSearch.class);
        verify(catalog).searchActiveProducts(search.capture());
        assertThat(search.getValue().categoryCode()).isEqualTo("T_SHIRT");
        assertThat(search.getValue().styleTag()).isEqualTo("简约");
        assertThat(search.getValue().occasionTag()).isEqualTo("通勤");
        assertThat(search.getValue().maxPrice()).isEqualByComparingTo("200");
        assertThat(result).contains("UNX-TEE-WHITE", "基础白色棉质 T 恤", "¥129", "简约");
    }

    @Test
    void tellsTheModelNotToInventProductsWhenNoCandidateExists() {
        FashionCatalogService catalog = mock(FashionCatalogService.class);
        when(catalog.searchActiveProducts(any())).thenReturn(List.of());

        String result = callback(new FashionCatalogTools(catalog, AiTraceLogger.disabled()), "search_fashion_products")
                .call("{\"categoryCode\":\"JACKET\"}");

        assertThat(result).contains("没有符合条件", "不要编造商品");
    }

    private static FashionProduct product() {
        return new FashionProduct(1L, "UNX-TEE-WHITE", "基础白色棉质 T 恤", "YKD Basics", "TOP", "T_SHIRT",
                "UNISEX", "白色", List.of(), List.of("简约", "休闲"), List.of("春夏"), List.of("通勤"), "棉", "常规",
                "纯色", new BigDecimal("129"), "CNY", "ACTIVE", 100, "SEED_CATALOG", "", "", "基础款", "",
                Instant.now(), Instant.now());
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
