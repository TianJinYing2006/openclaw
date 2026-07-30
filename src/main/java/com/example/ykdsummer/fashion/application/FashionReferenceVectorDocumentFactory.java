package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.fashion.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
        metadata.put("referenceLookId", look.id());
        metadata.put("schemaVersion", safe(look.annotationSchemaVersion()));
        metadata.put("status", safe(look.status()));
        return new Document(documentId(look.id()), text(look), metadata);
    }

    public String text(FashionReferenceLook look) {
        StringBuilder result = new StringBuilder("公共穿搭参考：").append(safe(look.displayName()));
        for (FashionReferenceGarment garment : look.garments()) {
            result.append("；单品：").append(safe(garment.displayName()))
                    .append("，类目=").append(safe(garment.categoryCode())).append('/').append(safe(garment.subCategoryCode()))
                    .append("，主色=").append(safe(garment.colorPrimary()))
                    .append("，风格=").append(labels(garment.styleTags()))
                    .append("，版型=").append(safe(garment.fitCode()))
                    .append("，图案=").append(safe(garment.patternCode()))
                    .append("，季节=").append(labels(garment.seasonTags()))
                    .append("，场景=").append(labels(garment.occasionTags()))
                    .append("，材质=").append(labels(garment.materialTags()));
        }
        return result.toString();
    }

    public String contentHash(FashionReferenceLook look) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text(look).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    public String documentId(long lookId) {
        return UUID.nameUUIDFromBytes(("fashion-reference:" + lookId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String labels(List<String> values) { return values == null || values.isEmpty() ? "未知" : String.join("、", values); }
    private static String safe(String value) { return value == null ? "" : value.replace('\0', ' ').strip(); }
}
