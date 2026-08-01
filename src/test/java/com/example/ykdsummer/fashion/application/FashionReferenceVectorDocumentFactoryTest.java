package com.example.ykdsummer.fashion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.fashion.FashionReferenceFixtures;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import org.junit.jupiter.api.Test;

class FashionReferenceVectorDocumentFactoryTest {
    @Test
    void marksPublicScopeAndIncludesEveryGarment() {
        FashionReferenceVectorDocumentFactory factory = new FashionReferenceVectorDocumentFactory();
        FashionReferenceLook look = FashionReferenceFixtures.look();

        var document = factory.document(look);
        var documents = factory.documents(look);

        assertThat(document.getMetadata()).containsEntry("scope", "PUBLIC_REFERENCE")
                .containsEntry("entityType", "REFERENCE_LOOK")
                .containsEntry("referenceLookId", 8L);
        assertThat(document.getText()).contains("浅灰色宽松短袖T恤", "深蓝直筒牛仔裤", "T_SHIRT", "JEANS");
        assertThat(documents).hasSize(3);
        assertThat(documents.subList(1, 3)).allSatisfy(value -> assertThat(value.getMetadata())
                .containsEntry("entityType", "REFERENCE_GARMENT")
                .containsEntry("referenceLookId", 8L));
        assertThat(documents.get(1).getText()).contains("浅灰色宽松短袖T恤", "TOP/T_SHIRT");
        assertThat(documents.get(2).getText()).contains("深蓝直筒牛仔裤", "BOTTOM/JEANS");
        assertThat(factory.contentHash(look)).hasSize(64);
    }

}
