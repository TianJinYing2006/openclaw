package com.wechatbot.fashion.wardrobe.domain;

import com.wechatbot.fashion.wardrobe.FashionReferenceFixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WardrobeSearchCriteriaTest {

    @Test
    void appliesTheSameGarmentFiltersToPublicReferenceLooks() {
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from("T恤", "浅灰色", List.of("简约"),
                "宽松", null, List.of("夏季"), List.of("日常"), "棉");

        assertThat(criteria.matches(FashionReferenceFixtures.look())).isTrue();
        assertThat(WardrobeSearchCriteria.from("外套", null, List.of(), null, null, List.of(), List.of(), null)
                .matches(FashionReferenceFixtures.look())).isFalse();
    }

    @Test
    void requiresEverySuppliedConditionAndNormalizesChineseLabels() {
        WardrobeItem jeans = item(1L, "JEANS", "深蓝", List.of("白色"), List.of("简约", "通勤"), "宽松", "纯色",
                List.of("春季", "秋季"), List.of("通勤", "面试"), "牛仔");
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from("牛仔裤", "深蓝", List.of("简约", "通勤"),
                "宽松", "纯色", List.of("春季", "秋季"), List.of("通勤", "面试"), "牛仔");

        assertThat(criteria.matches(jeans)).isTrue();
        assertThat(WardrobeSearchCriteria.from("牛仔裤", "黑色", List.of(), null, null, List.of(), List.of(), null)
                .matches(jeans)).isFalse();
        assertThat(WardrobeSearchCriteria.from("裤子", null, List.of(), null, null, List.of(), List.of(), null)
                .matches(jeans)).isTrue();
    }

    @Test
    void expandsCommonParentCategoriesWithoutMatchingUnrelatedItems() {
        WardrobeItem jacket = item(2L, "JACKET", "BLACK", List.of(), List.of(), "", "", List.of(), List.of(), "");
        WardrobeItem shirt = item(3L, "SHIRT", "WHITE", List.of(), List.of(), "", "", List.of(), List.of(), "");

        assertThat(WardrobeSearchCriteria.from("外套", null, List.of(), null, null, List.of(), List.of(), null)
                .matches(jacket)).isTrue();
        assertThat(WardrobeSearchCriteria.from("外套", null, List.of(), null, null, List.of(), List.of(), null)
                .matches(shirt)).isFalse();
    }

    private static WardrobeItem item(long id, String category, String color, List<String> secondaryColors, List<String> styles,
                                     String fit, String pattern, List<String> seasons, List<String> occasions, String material) {
        Instant now = Instant.now();
        return new WardrobeItem(id, 1L, "instance", category, color, secondaryColors, styles, fit, pattern, seasons,
                occasions, material, "ACTIVE", "SUCCEEDED", 1, null, "USER_CONFIRMED", "", now, now);
    }
}
