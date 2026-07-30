package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FashionItemNamerTest {

    @Test
    void createsAReadableNameWithoutLeakingInternalCodes() {
        String name = FashionItemNamer.nameFor("T_SHIRT", "GRAY",
                "灰色宽松圆领短袖T恤，胸前有黑色线条字母印花", "RELAXED", "PRINTED");

        assertThat(name).isEqualTo("灰色字母印花宽松短袖T恤");
    }

    @Test
    void createsAUsefulNameForPantsAndOuterwear() {
        assertThat(FashionItemNamer.nameFor("JEANS", "DENIM_BLUE", "宽松阔腿牛仔裤", "RELAXED", ""))
                .isEqualTo("浅蓝色宽松牛仔裤");
        assertThat(FashionItemNamer.nameFor("JACKET", "NAVY", "通勤外套", "", ""))
                .isEqualTo("藏青色外套");
    }
}
