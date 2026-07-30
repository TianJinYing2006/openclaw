package com.example.ykdsummer.fashion;

import com.example.ykdsummer.fashion.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FashionReferenceFixtures {
    private FashionReferenceFixtures() { }

    public static FashionReferenceLook look() {
        FashionReferenceGarment top = garment(1, "浅灰色宽松短袖T恤", "TOP", "T_SHIRT", "LIGHT_GRAY",
                List.of("MINIMAL", "CASUAL"), List.of("SUMMER"), List.of("DAILY"), List.of("COTTON"));
        FashionReferenceGarment jeans = garment(2, "深蓝直筒牛仔裤", "BOTTOM", "JEANS", "DENIM_BLUE",
                List.of("CASUAL"), List.of("ALL_SEASON"), List.of("DAILY"), List.of("DENIM"));
        return new FashionReferenceLook(8L, "look-8", "浅灰T恤 + 深蓝牛仔裤", "img_public0001", 1,
                "image/png", "look.png", "", "LOCAL_IMPORT", "LOCAL_DEVELOPMENT_ONLY", "a".repeat(64), "",
                "1.0.0", "{}", "ACTIVE", List.of(top, jeans), Instant.now(), Instant.now());
    }

    private static FashionReferenceGarment garment(int index, String name, String parent, String category,
            String color, List<String> styles, List<String> seasons, List<String> occasions, List<String> materials) {
        return new FashionReferenceGarment(index, 8L, index, name, parent, category, "UNISEX", color,
                List.of(), List.of(), styles, "RELAXED", "SOLID", "H_LINE", "REGULAR", materials,
                seasons, occasions, 1, "FULL", new BigDecimal("0.9"), new BigDecimal("0.9"), "{}");
    }
}
