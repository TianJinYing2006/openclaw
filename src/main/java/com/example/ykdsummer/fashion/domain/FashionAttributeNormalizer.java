package com.example.ykdsummer.fashion.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Shared canonical vocabulary for structured filters, recommendation evidence, and user-entered Chinese tags. */
public final class FashionAttributeNormalizer {
    private FashionAttributeNormalizer() { }

    public static String token(String value) {
        String normalized = value == null ? "" : value.replace('\u0000', ' ').strip()
                .replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        String compact = normalized.replace("_", "");
        return switch (normalized) {
            case "上衣", "上装" -> "TOP";
            case "下装" -> "BOTTOM";
            case "裤子", "长裤" -> "PANTS";
            case "外套" -> "OUTERWEAR";
            case "T恤", "短袖" -> "T_SHIRT";
            case "衬衫" -> "SHIRT";
            case "针织衫", "毛衣" -> "KNITWEAR";
            case "夹克" -> "JACKET";
            case "牛仔裤" -> "JEANS";
            case "直筒裤" -> "STRAIGHT_PANTS";
            case "裙子", "半身裙" -> "SKIRT";
            case "连衣裙" -> "DRESS";
            case "鞋", "鞋子" -> "SHOES";
            case "包", "包袋" -> "BAG";
            case "黑", "黑色" -> "BLACK";
            case "白", "白色" -> "WHITE";
            case "灰", "灰色", "GREY" -> "GRAY";
            case "浅灰", "浅灰色" -> "LIGHT_GRAY";
            case "深灰", "深灰色" -> "DARK_GRAY";
            case "炭灰", "炭灰色" -> "CHARCOAL";
            case "蓝灰", "蓝灰色" -> "BLUE_GRAY";
            case "蓝", "蓝色" -> "BLUE";
            case "深蓝", "深蓝色", "藏青", "藏青色" -> "NAVY";
            case "牛仔蓝", "牛仔蓝色" -> "DENIM_BLUE";
            case "红", "红色" -> "RED";
            case "绿", "绿色" -> "GREEN";
            case "黄", "黄色" -> "YELLOW";
            case "棕", "棕色", "咖色", "咖啡色" -> "BROWN";
            case "卡其", "卡其色" -> "KHAKI";
            case "驼色" -> "CAMEL";
            case "米白", "米白色", "象牙白" -> "OFF_WHITE";
            case "米色" -> "BEIGE";
            case "简约" -> "MINIMAL";
            case "休闲", "日常休闲" -> "CASUAL";
            case "通勤" -> "COMMUTE";
            case "正式", "正式场合" -> "FORMAL";
            case "商务" -> "BUSINESS";
            case "街头" -> "STREET";
            case "运动" -> "SPORT";
            case "复古" -> "RETRO";
            case "面试" -> "INTERVIEW";
            case "约会" -> "DATE";
            case "日常" -> "DAILY";
            case "上课", "校园" -> "SCHOOL";
            case "旅行", "出行" -> "TRAVEL";
            case "户外" -> "OUTDOOR";
            case "春", "春季", "春天" -> "SPRING";
            case "夏", "夏季", "夏天" -> "SUMMER";
            case "秋", "秋季", "秋天" -> "AUTUMN";
            case "冬", "冬季", "冬天" -> "WINTER";
            case "春秋", "春秋季" -> "SPRING_AUTUMN";
            case "四季", "四季通用" -> "ALL_SEASON";
            case "宽松" -> "RELAXED";
            case "修身", "紧身" -> "SLIM";
            case "直筒" -> "STRAIGHT";
            case "常规", "合身" -> "REGULAR";
            case "纯色" -> "SOLID";
            case "印花", "图案" -> "PRINT";
            case "条纹" -> "STRIPED";
            case "格纹", "格子" -> "CHECKED";
            case "棉", "棉质" -> "COTTON";
            case "牛仔", "牛仔布", "丹宁" -> "DENIM";
            case "针织" -> "KNIT";
            case "羊毛" -> "WOOL";
            case "皮革", "皮质" -> "LEATHER";
            case "亚麻" -> "LINEN";
            case "颜色" -> "COLOR";
            case "风格" -> "STYLE";
            case "版型" -> "FIT";
            case "图案类型" -> "PATTERN";
            case "材质" -> "MATERIAL";
            case "类目", "类别" -> "CATEGORY";
            default -> switch (compact) {
                case "TSHIRT", "TEE" -> "T_SHIRT";
                case "LIGHTGRAY" -> "LIGHT_GRAY";
                case "DARKGRAY" -> "DARK_GRAY";
                case "CHARCOALGRAY" -> "CHARCOAL";
                case "BLUEGRAY" -> "BLUE_GRAY";
                case "NAVYBLUE", "DARKBLUE" -> "NAVY";
                case "DENIMBLUE" -> "DENIM_BLUE";
                case "OFFWHITE" -> "OFF_WHITE";
                case "CAMELCOLOR" -> "CAMEL";
                default -> normalized;
            };
        };
    }

    public static Set<String> tokens(Collection<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = token(value);
            switch (normalized) {
                case "" -> { }
                case "SPRING_AUTUMN" -> {
                    result.add("SPRING");
                    result.add("AUTUMN");
                }
                case "ALL_SEASON" -> {
                    result.add("SPRING");
                    result.add("SUMMER");
                    result.add("AUTUMN");
                    result.add("WINTER");
                }
                default -> result.add(normalized);
            }
        }
        return java.util.Collections.unmodifiableSet(result);
    }
}
