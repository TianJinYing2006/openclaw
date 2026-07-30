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

        assertThat(document.getMetadata()).containsEntry("scope", "PUBLIC_REFERENCE")
                .containsEntry("referenceLookId", 8L);
        assertThat(document.getText()).contains("浅灰色宽松短袖T恤", "深蓝直筒牛仔裤", "T_SHIRT", "JEANS");
        assertThat(factory.contentHash(look)).hasSize(64);
    }

}
