package com.wechatbot.fashion.wardrobe.application;

import com.wechatbot.fashion.wardrobe.domain.FashionReferenceGarment;
import com.wechatbot.fashion.wardrobe.domain.FashionReferenceLook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class FashionReferenceVectorDocumentFactory {
    public Document document(FashionReferenceLook look) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", "PUBLIC_REFERENCE");
        metadata.put("entityType", "REFERENCE_LOOK");
        metadata.put("referenceLookId", look.id());
        metadata.put("schemaVersion", safe(look.annotationSchemaVersion()));
        metadata.put("status", safe(look.status()));
        return new Document(documentId(look.id()), text(look), metadata);
    }

    public List<Document> documents(FashionReferenceLook look) {
        List<Document> result = new ArrayList<>();
        result.add(document(look));
        look.garments().forEach(garment -> result.add(garmentDocument(look, garment)));
        return List.copyOf(result);
    }

    public Document garmentDocument(FashionReferenceLook look, FashionReferenceGarment garment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scope", "PUBLIC_REFERENCE");
        metadata.put("entityType", "REFERENCE_GARMENT");
        metadata.put("referenceLookId", look.id());
        metadata.put("referenceGarmentId", garment.id());
        metadata.put("itemIndex", garment.itemIndex());
        metadata.put("categoryCode", safe(garment.categoryCode()));
        metadata.put("subCategoryCode", safe(garment.subCategoryCode()));
        metadata.put("colorPrimary", safe(garment.colorPrimary()));
        metadata.put("cutoutStatus", safe(garment.cutoutStatus()));
        metadata.put("schemaVersion", safe(look.annotationSchemaVersion()));
        metadata.put("status", safe(look.status()));
        return new Document(garmentDocumentId(look.id(), garment.itemIndex()), garmentText(garment), metadata);
    }

    public String text(FashionReferenceLook look) {
        StringBuilder result = new StringBuilder("公共穿搭参考：").append(safe(look.displayName()));
        for (FashionReferenceGarment garment : look.garments()) {
            result.append("；").append(garmentText(garment));
        }
        return result.toString();
    }

    public String garmentText(FashionReferenceGarment garment) {
        return new StringBuilder("公共穿搭单品：").append(safe(garment.displayName()))
                .append("，类目=").append(safe(garment.categoryCode())).append('/').append(safe(garment.subCategoryCode()))
                .append("，主色=").append(safe(garment.colorPrimary()))
                .append("，风格=").append(labels(garment.styleTags()))
                .append("，版型=").append(safe(garment.fitCode()))
                .append("，图案=").append(safe(garment.patternCode()))
                .append("，廓形=").append(safe(garment.silhouetteCode()))
                .append("，长度=").append(safe(garment.lengthCode()))
                .append("，季节=").append(labels(garment.seasonTags()))
                .append("，场景=").append(labels(garment.occasionTags()))
                .append("，材质=").append(labels(garment.materialTags()))
                .append("，正式度=").append(garment.formalityLevel())
                .toString();
    }

    public String contentHash(FashionReferenceLook look) {
        String content = String.join("\n", documents(look).stream().map(Document::getText).toList());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    public String documentId(long lookId) {
        return UUID.nameUUIDFromBytes(("fashion-reference:" + lookId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public String garmentDocumentId(long lookId, int itemIndex) {
        return UUID.nameUUIDFromBytes(("fashion-reference-garment:" + lookId + ":" + Math.max(1, itemIndex))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    public List<String> allDocumentIds(long lookId, int maximumGarments) {
        List<String> ids = new ArrayList<>();
        ids.add(documentId(lookId));
        for (int index = 1; index <= Math.max(1, maximumGarments); index++) {
            ids.add(garmentDocumentId(lookId, index));
        }
        return List.copyOf(ids);
    }
    private static String labels(List<String> values) { return values == null || values.isEmpty() ? "未知" : String.join("、", values); }
    private static String safe(String value) { return value == null ? "" : value.replace('\0', ' ').strip(); }
}
