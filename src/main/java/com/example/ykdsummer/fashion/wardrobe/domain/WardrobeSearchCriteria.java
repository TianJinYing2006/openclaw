package com.example.ykdsummer.fashion.wardrobe.domain;

import java.util.List;
import java.util.Set;

/** Structured AND criteria for a single user's confirmed wardrobe. */
public record WardrobeSearchCriteria(
        Set<String> categoryCodes,
        String color,
        Set<String> styleTags,
        String fitCode,
        String patternCode,
        Set<String> seasonTags,
        Set<String> occasionTags,
        String material
) {
    public WardrobeSearchCriteria {
        categoryCodes = categoryCodes == null ? Set.of() : Set.copyOf(categoryCodes);
        color = token(color);
        styleTags = values(styleTags);
        fitCode = token(fitCode);
        patternCode = token(patternCode);
        seasonTags = values(seasonTags);
        occasionTags = values(occasionTags);
        material = token(material);
    }

    public static WardrobeSearchCriteria from(
            String category, String color, List<String> styles, String fit, String pattern,
            List<String> seasons, List<String> occasions, String material
    ) {
        return new WardrobeSearchCriteria(categories(category), color, asSet(styles), fit, pattern,
                asSet(seasons), asSet(occasions), material);
    }

    public boolean hasFilters() {
        return !categoryCodes.isEmpty() || !color.isBlank() || !styleTags.isEmpty() || !fitCode.isBlank()
                || !patternCode.isBlank() || !seasonTags.isEmpty() || !occasionTags.isEmpty() || !material.isBlank();
    }

    /** Every supplied condition is an AND condition; multi-value tags must all be present. */
    public boolean matches(WardrobeItem item) {
        if (item == null) return false;
        return matches(item.parentCategoryCode(), item.categoryCode(), item.colorPrimary(), item.secondaryColors(),
                item.styleTags(), item.fitCode(), item.patternCode(), item.seasonTags(), item.occasionTags(),
                item.material().isBlank() ? List.of() : List.of(item.material()));
    }

    public boolean matches(FashionReferenceGarment garment) {
        if (garment == null) return false;
        return matches(garment.categoryCode(), garment.subCategoryCode(), garment.colorPrimary(),
                garment.secondaryColors(), garment.styleTags(), garment.fitCode(), garment.patternCode(),
                garment.seasonTags(), garment.occasionTags(), garment.materialTags());
    }

    public boolean matches(FashionReferenceLook look) {
        return look != null && look.garments().stream().anyMatch(this::matches);
    }

    private boolean matches(
            String parentCategory, String subCategory, String primaryColor, List<String> secondaryColors,
            List<String> styles, String fit, String pattern, List<String> seasons, List<String> occasions,
            List<String> materials
    ) {
        return (categoryCodes.isEmpty() || categoryCodes.contains(token(parentCategory))
                || categoryCodes.contains(token(subCategory)))
                && (color.isBlank() || color.equals(token(primaryColor)) || contains(secondaryColors, color))
                && containsAll(styles, styleTags)
                && (fitCode.isBlank() || fitCode.equals(token(fit)))
                && (patternCode.isBlank() || patternCode.equals(token(pattern)))
                && containsAll(seasons, seasonTags)
                && containsAll(occasions, occasionTags)
                && (material.isBlank() || contains(materials, material));
    }

    public String summary() {
        List<String> parts = new java.util.ArrayList<>();
        if (!categoryCodes.isEmpty()) parts.add("类目=" + String.join("/", categoryCodes));
        if (!color.isBlank()) parts.add("颜色=" + color);
        if (!styleTags.isEmpty()) parts.add("风格=" + String.join("+", styleTags));
        if (!fitCode.isBlank()) parts.add("版型=" + fitCode);
        if (!patternCode.isBlank()) parts.add("图案=" + patternCode);
        if (!seasonTags.isEmpty()) parts.add("季节=" + String.join("+", seasonTags));
        if (!occasionTags.isEmpty()) parts.add("场景=" + String.join("+", occasionTags));
        if (!material.isBlank()) parts.add("材质=" + material);
        return parts.isEmpty() ? "全部单品" : String.join("，", parts);
    }

    private static boolean containsAll(List<String> actual, Set<String> expected) {
        if (expected.isEmpty()) return true;
        Set<String> actualValues = FashionAttributeNormalizer.tokens(actual);
        return actualValues.containsAll(expected);
    }

    private static boolean contains(List<String> actual, String expected) {
        return actual != null && actual.stream().map(WardrobeSearchCriteria::token).anyMatch(expected::equals);
    }

    private static Set<String> categories(String value) {
        return switch (token(value)) {
            case "TOP" -> Set.of("TOP", "T_SHIRT", "SHIRT", "KNITWEAR");
            case "OUTERWEAR" -> Set.of("OUTERWEAR", "JACKET");
            case "BOTTOM" -> Set.of("BOTTOM", "JEANS", "STRAIGHT_PANTS", "SKIRT");
            case "PANTS" -> Set.of("JEANS", "STRAIGHT_PANTS");
            case "SHOES" -> Set.of("SHOES", "SNEAKERS", "LOAFERS");
            case "" -> Set.of();
            default -> Set.of(token(value));
        };
    }

    private static Set<String> asSet(List<String> values) {
        return FashionAttributeNormalizer.tokens(values);
    }

    private static Set<String> values(Set<String> values) {
        return FashionAttributeNormalizer.tokens(values);
    }

    private static String token(String value) {
        return FashionAttributeNormalizer.token(value);
    }
}
