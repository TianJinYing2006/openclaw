package com.wechatbot.fashion.wardrobe.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionAttributeNormalizerTest {

    @Test
    void sharesChineseCanonicalValuesAcrossSearchAndRecommendation() {
        assertThat(FashionAttributeNormalizer.token("浅灰色")).isEqualTo("LIGHT_GRAY");
        assertThat(FashionAttributeNormalizer.token("通勤")).isEqualTo("COMMUTE");
        assertThat(FashionAttributeNormalizer.token("宽松")).isEqualTo("RELAXED");
        assertThat(FashionAttributeNormalizer.tokens(List.of("春秋")))
                .containsExactly("SPRING", "AUTUMN");
    }

    @Test
    void treatsAllSeasonWardrobeItemsAsCompatibleWithASummerFilter() {
        WardrobeItem item = new WardrobeItem(1L, 1L, "instance", "牛仔蓝直筒裤",
                "BOTTOM", "JEANS", "DENIM_BLUE", List.of(), List.of("CASUAL"),
                "STRAIGHT", "SOLID", List.of("ALL_SEASON"), List.of("DAILY"),
                "DENIM", "ACTIVE", "SUCCEEDED", 1, null,
                "USER_CONFIRMED", "", "1.0.0", "{}", Instant.now(), Instant.now());
        WardrobeSearchCriteria criteria = WardrobeSearchCriteria.from(
                "裤子", null, List.of(), null, null,
                List.of("夏季"), List.of(), null);

        assertThat(criteria.matches(item)).isTrue();
    }
}
