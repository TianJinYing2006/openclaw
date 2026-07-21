package com.example.ykdsummer.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiFileMediaTypesTest {

    @Test
    void mapsEverySupportedExtensionWithRelayCompatibleXmlAndJavaTypes() {
        assertThat(AiFileMediaTypes.forFileName("a.txt")).contains("text/plain");
        assertThat(AiFileMediaTypes.forFileName("a.MD")).contains("text/markdown");
        assertThat(AiFileMediaTypes.forFileName("a.json")).contains("application/json");
        assertThat(AiFileMediaTypes.forFileName("a.csv")).contains("text/csv");
        assertThat(AiFileMediaTypes.forFileName("a.html")).contains("text/html");
        assertThat(AiFileMediaTypes.forFileName("a.xml")).contains("text/xml");
        assertThat(AiFileMediaTypes.forFileName("Demo.java")).contains("text/x-java");
        assertThat(AiFileMediaTypes.forFileName("a.pdf")).contains("application/pdf");
        assertThat(AiFileMediaTypes.forFileName("a.doc")).contains("application/msword");
        assertThat(AiFileMediaTypes.forFileName("a.docx"))
                .contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(AiFileMediaTypes.forFileName("a.xlsx"))
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(AiFileMediaTypes.forFileName("a.pptx"))
                .contains("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(AiFileMediaTypes.forFileName("a.exe")).isEmpty();
    }
}
