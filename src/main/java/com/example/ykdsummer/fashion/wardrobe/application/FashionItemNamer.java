package com.example.ykdsummer.fashion.wardrobe.application;

import java.util.Locale;

/** Builds concise Chinese wardrobe names from the structured labels already stored for an item. */
public final class FashionItemNamer {
    private FashionItemNamer() { }

    public static String nameFor(String categoryCode, String colorCode, String evidence, String fitCode, String patternCode) {
        String details = safe(evidence);
        return colorLabel(colorCode) + patternLabel(patternCode, details) + fitLabel(fitCode, details)
                + categoryLabel(categoryCode, details);
    }

    public static String categoryLabel(String categoryCode, String evidence) {
        String details = safe(evidence);
        return switch (safe(categoryCode).toUpperCase(Locale.ROOT)) {
            case "T_SHIRT", "T恤", "短袖" -> contains(details, "Polo", "polo") ? "Polo衫"
                    : contains(details, "短袖") ? "短袖T恤" : "T恤";
            case "SHIRT", "衬衫" -> "衬衫";
            case "KNITWEAR", "针织衫" -> "针织衫";
            case "JACKET", "外套", "夹克" -> contains(details, "西装") ? "西装外套"
                    : contains(details, "夹克") ? "夹克" : "外套";
            case "JEANS", "牛仔裤" -> "牛仔裤";
            case "STRAIGHT_PANTS", "裤子", "长裤", "直筒裤" -> contains(details, "阔腿") ? "阔腿裤"
                    : contains(details, "直筒") ? "直筒裤" : "长裤";
            case "SKIRT" -> "半身裙";
            case "DRESS" -> "连衣裙";
            case "OUTFIT", "套装", "整套", "SUIT" -> "整套穿搭";
            case "SHOES" -> contains(details, "帆布") ? "帆布鞋"
                    : contains(details, "运动") ? "运动鞋"
                    : contains(details, "皮鞋") ? "皮鞋" : "鞋子";
            case "BAG" -> "包";
            case "ACCESSORY" -> "配饰";
            default -> "衣物";
        };
    }

    public static String colorLabel(String colorCode) {
        return switch (safe(colorCode).toUpperCase(Locale.ROOT)) {
            case "BLACK", "黑色" -> "黑色";
            case "WHITE", "白色" -> "白色";
            case "OFF_WHITE", "米白", "米白色" -> "米白色";
            case "GRAY", "GREY", "灰色", "浅灰", "浅灰色", "花灰" -> "灰色";
            case "BLUE", "蓝色" -> "蓝色";
            case "DENIM_BLUE", "牛仔蓝", "浅蓝", "浅蓝色" -> "浅蓝色";
            case "NAVY", "深蓝", "深蓝色", "藏青", "藏青色" -> "藏青色";
            case "BROWN", "棕色" -> "棕色";
            case "KHAKI", "卡其", "卡其色" -> "卡其色";
            case "RED", "红色" -> "红色";
            case "GREEN", "绿色" -> "绿色";
            case "YELLOW", "黄色" -> "黄色";
            default -> "";
        };
    }

    public static String fitLabel(String fitCode, String evidence) {
        String normalized = safe(fitCode).toUpperCase(Locale.ROOT);
        if ("RELAXED".equals(normalized) || contains(evidence, "宽松", "Oversize", "oversize")) return "宽松";
        if ("SLIM".equals(normalized) || contains(evidence, "修身")) return "修身";
        return "";
    }

    public static String patternLabel(String patternCode, String evidence) {
        if (contains(evidence, "字母", "英文")) return "字母印花";
        if ("STRIPED".equalsIgnoreCase(safe(patternCode)) || contains(evidence, "STRIPED", "条纹")) return "条纹";
        if ("CHECKED".equalsIgnoreCase(safe(patternCode)) || contains(evidence, "CHECKED", "格纹", "格子")) return "格纹";
        if ("PRINTED".equalsIgnoreCase(safe(patternCode)) || contains(evidence, "PRINTED", "印花", "图案")) return "印花";
        return "";
    }

    private static boolean contains(String value, String... terms) {
        String text = safe(value);
        for (String term : terms) {
            if (!term.isBlank() && text.contains(term)) return true;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\0', ' ').strip();
    }
}
