package com.example.ykdsummer.fashion.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        Set<String> actualValues = actual == null ? Set.of() : actual.stream().map(WardrobeSearchCriteria::token)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
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
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream().map(WardrobeSearchCriteria::token).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> values(Set<String> values) {
        return values == null || values.isEmpty() ? Set.of() : values.stream().map(WardrobeSearchCriteria::token)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String token(String value) {
        String raw = value == null ? "" : value.replace('\u0000', ' ').strip()
                .replace(" ", "").replace("-", "_").toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "T恤", "短袖", "TEE", "TSHIRT" -> "T_SHIRT";
            case "衬衫" -> "SHIRT";
            case "针织衫", "毛衣" -> "KNITWEAR";
            case "上装" -> "TOP";
            case "外套", "夹克" -> "OUTERWEAR";
            case "下装" -> "BOTTOM";
            case "裤子", "长裤" -> "PANTS";
            case "牛仔裤" -> "JEANS";
            case "直筒裤" -> "STRAIGHT_PANTS";
            case "裙子", "半身裙" -> "SKIRT";
            case "连衣裙" -> "DRESS";
            case "鞋", "鞋子" -> "SHOES";
            case "包", "包袋" -> "BAG";
            case "黑", "黑色" -> "BLACK";
            case "白", "白色" -> "WHITE";
            case "灰", "灰色", "GREY" -> "GRAY";
            case "浅灰", "浅灰色", "LIGHTGRAY" -> "LIGHT_GRAY";
            case "深灰", "深灰色", "DARKGRAY" -> "DARK_GRAY";
            case "炭灰", "炭灰色", "CHARCOALGRAY" -> "CHARCOAL";
            case "蓝灰", "蓝灰色", "BLUEGRAY" -> "BLUE_GRAY";
            case "蓝", "蓝色" -> "BLUE";
            case "深蓝", "深蓝色", "藏青", "藏青色", "NAVYBLUE", "DARK_BLUE" -> "NAVY";
            case "牛仔蓝", "牛仔蓝色", "DENIMBLUE" -> "DENIM_BLUE";
            case "红", "红色" -> "RED";
            case "绿", "绿色" -> "GREEN";
            case "黄", "黄色" -> "YELLOW";
            case "棕", "棕色", "咖啡色" -> "BROWN";
            case "卡其", "卡其色" -> "KHAKI";
            case "驼色", "CAMELCOLOR" -> "CAMEL";
            case "米白", "象牙白", "OFFWHITE" -> "OFF_WHITE";
            case "简约" -> "MINIMAL";
            case "通勤" -> "COMMUTE";
            case "宽松" -> "RELAXED";
            case "修身" -> "SLIM";
            case "直筒" -> "STRAIGHT";
            case "纯色" -> "SOLID";
            case "条纹" -> "STRIPED";
            case "格纹", "格子" -> "CHECKED";
            case "春季" -> "SPRING";
            case "夏季" -> "SUMMER";
            case "秋季" -> "AUTUMN";
            case "冬季" -> "WINTER";
            case "面试" -> "INTERVIEW";
            case "约会" -> "DATE";
            case "日常" -> "DAILY";
            case "旅行", "出行" -> "TRAVEL";
            case "户外" -> "OUTDOOR";
            case "正式", "正式场合" -> "FORMAL";
            case "运动" -> "SPORT";
            case "棉", "棉质" -> "COTTON";
            case "牛仔", "丹宁" -> "DENIM";
            default -> raw;
        };
    }
}
