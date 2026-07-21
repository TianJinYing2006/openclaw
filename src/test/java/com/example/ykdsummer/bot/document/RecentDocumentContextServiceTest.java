package com.example.ykdsummer.bot.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecentDocumentContextServiceTest {

    @Test
    void selectsGeneratedOrSourceContextAndLeavesUnrelatedChatUntouched() {
        RecentDocumentContextService service = new RecentDocumentContextService();
        service.remember("user", "source.docx", "原文内容", RecentDocumentContextService.Kind.SOURCE);
        service.remember("user", "generated.pdf", "生成后的建议内容", RecentDocumentContextService.Kind.GENERATED);

        assertThat(service.augmentIfRelevant("user", "刚才生成的文档讲了什么"))
                .contains("generated.pdf", "kind=\"generated\"", "生成后的建议内容", "用户当前问题");
        assertThat(service.augmentIfRelevant("user", "刚才上传的原文是什么"))
                .contains("source.docx", "kind=\"source\"", "原文内容");
        assertThat(service.augmentIfRelevant("user", "今天天气怎么样"))
                .isEqualTo("今天天气怎么样");

        assertThat(service.referenceForGeneration("user", "生成 PDF：整理成建议"))
                .get().extracting(RecentDocumentContextService.RecentDocument::fileName)
                .isEqualTo("generated.pdf");
        assertThat(service.referenceForGeneration("user", "根据上传的原文生成 Word"))
                .get().extracting(RecentDocumentContextService.RecentDocument::fileName)
                .isEqualTo("source.docx");

        service.clear("user");
        assertThat(service.hasRecent("user")).isFalse();
    }
}
