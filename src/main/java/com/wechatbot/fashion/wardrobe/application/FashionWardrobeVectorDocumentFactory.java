package com.wechatbot.fashion.wardrobe.application;

import com.wechatbot.fashion.wardrobe.domain.WardrobeItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/** Converts structured MySQL wardrobe facts into stable text for semantic retrieval. */
@Component
public class FashionWardrobeVectorDocumentFactory {

    public Document document(WardrobeItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("wardrobeItemId", item.id());
        metadata.put("appUserId", item.appUserId());
        metadata.put("scope", "USER_WARDROBE");
        if (item.instanceId() != null && !item.instanceId().isBlank()) metadata.put("instanceId", item.instanceId());
        metadata.put("categoryCode", safe(item.categoryCode()));
        metadata.put("colorPrimary", safe(item.colorPrimary()));
        metadata.put("itemStatus", safe(item.itemStatus()));
        metadata.put("schemaVersion", safe(item.annotationSchemaVersion()));
        return new Document(documentId(item.id()), text(item), metadata);
    }

    public String text(WardrobeItem item) {
        String name = safe(item.displayName());
        if (name.isBlank()) name = FashionItemNamer.nameFor(item.categoryCode(), item.colorPrimary(), item.attributesJson(),
                item.fitCode(), item.patternCode());
        return String.join("；",
                "服装名称：" + name,
                "类目：" + categoryLabel(item.categoryCode()) + "（" + safe(item.categoryCode()) + "）",
                "主色：" + valueLabel(item.colorPrimary()),
                "次要颜色：" + labels(item.secondaryColors()),
                "风格：" + labels(item.styleTags()),
                "版型：" + valueLabel(item.fitCode()),
                "图案：" + valueLabel(item.patternCode()),
                "季节：" + labels(item.seasonTags()),
                "适用场景：" + labels(item.occasionTags()),
                "材质：" + valueLabel(item.material()),
                "用户备注：" + emptyAsUnknown(item.notes()));
    }

    public String contentHash(WardrobeItem item) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text(item).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public String documentId(long wardrobeItemId) {
        return UUID.nameUUIDFromBytes(("fashion-wardrobe:" + wardrobeItemId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String labels(List<String> values) {
        if (values == null || values.isEmpty()) return "未知";
        String joined = values.stream().map(FashionWardrobeVectorDocumentFactory::valueLabel)
                .filter(value -> !value.isBlank()).distinct().reduce((a, b) -> a + "、" + b).orElse("");
        return emptyAsUnknown(joined);
    }

    private static String categoryLabel(String value) {
        return switch (token(value)) {
            case "T_SHIRT" -> "T恤/短袖";
            case "SHIRT" -> "衬衫";
            case "KNITWEAR" -> "针织衫/毛衣";
            case "OUTERWEAR" -> "外套";
            case "JACKET" -> "夹克";
            case "JEANS" -> "牛仔裤";
            case "STRAIGHT_PANTS" -> "直筒裤/长裤";
            case "SKIRT" -> "半身裙";
            case "DRESS" -> "连衣裙";
            case "SHOES" -> "鞋履";
            case "SNEAKERS" -> "运动鞋";
            case "LOAFERS" -> "乐福鞋";
            case "BAG" -> "包袋";
            case "ACCESSORY" -> "配饰";
            default -> emptyAsUnknown(value);
        };
    }

    private static String valueLabel(String value) {
        return switch (token(value)) {
            case "BLACK" -> "黑色";
            case "WHITE" -> "白色";
            case "GRAY", "GREY" -> "灰色";
            case "BLUE" -> "蓝色";
            case "NAVY" -> "藏青色";
            case "DENIM_BLUE" -> "牛仔蓝";
            case "RED" -> "红色";
            case "GREEN" -> "绿色";
            case "YELLOW" -> "黄色";
            case "BROWN" -> "棕色";
            case "KHAKI" -> "卡其色";
            case "OFF_WHITE" -> "米白色";
            case "MINIMAL" -> "简约";
            case "CASUAL" -> "休闲";
            case "COMMUTE" -> "通勤";
            case "RELAXED" -> "宽松";
            case "SLIM" -> "修身";
            case "STRAIGHT" -> "直筒";
            case "SOLID" -> "纯色";
            case "STRIPED" -> "条纹";
            case "CHECKED" -> "格纹";
            case "SPRING" -> "春季";
            case "SUMMER" -> "夏季";
            case "AUTUMN" -> "秋季";
            case "WINTER" -> "冬季";
            case "INTERVIEW" -> "面试";
            case "DATE" -> "约会";
            case "FORMAL" -> "正式场合";
            case "SPORT" -> "运动";
            case "COTTON" -> "棉";
            case "DENIM" -> "牛仔";
            case "KNIT" -> "针织";
            case "WOOL" -> "羊毛";
            case "LEATHER" -> "皮革";
            case "LINEN" -> "亚麻";
            default -> emptyAsUnknown(value);
        };
    }

    private static String token(String value) {
        return safe(value).toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String emptyAsUnknown(String value) {
        String cleaned = safe(value);
        return cleaned.isBlank() ? "未知" : cleaned;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
