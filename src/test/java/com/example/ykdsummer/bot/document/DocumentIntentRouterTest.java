package com.example.ykdsummer.bot.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIntentRouterTest {

    private final DocumentIntentRouter router = new DocumentIntentRouter();

    @Test
    void separatesAnalysisEditAndAmbiguousStatements() {
        assertThat(router.route("我认为第二自然段有问题").intent())
                .isEqualTo(DocumentIntentRouter.Intent.ANALYZE);
        assertThat(router.route("把第二自然段改短").intent())
                .isEqualTo(DocumentIntentRouter.Intent.EDIT);
        assertThat(router.route("生成一个你的理解word给我").intent())
                .isEqualTo(DocumentIntentRouter.Intent.GENERATE);
        assertThat(router.route("第二自然段").intent())
                .isEqualTo(DocumentIntentRouter.Intent.AMBIGUOUS);
    }

    @Test
    void recognizesNaturalFileRequestsWithoutCommandPrefixes() {
        assertThat(router.route("帮我根据这份材料写一份项目周报").intent())
                .isEqualTo(DocumentIntentRouter.Intent.GENERATE);
        assertThat(router.route("给我做一个关于它的建议文案").intent())
                .isEqualTo(DocumentIntentRouter.Intent.GENERATE);
        assertThat(router.route("整理出一个 Excel 表格").intent())
                .isEqualTo(DocumentIntentRouter.Intent.GENERATE);
        assertThat(router.route("把第三段补充完整").intent())
                .isEqualTo(DocumentIntentRouter.Intent.EDIT);
        assertThat(router.route("总结一下它的核心观点").intent())
                .isEqualTo(DocumentIntentRouter.Intent.ANALYZE);
        assertThat(router.route("帮我看看这份材料").intent())
                .isEqualTo(DocumentIntentRouter.Intent.ANALYZE);
        assertThat(router.route("刚才生成的文件有什么问题").intent())
                .isEqualTo(DocumentIntentRouter.Intent.ANALYZE);
        assertThat(router.route("这个文件是怎么生成的？").intent())
                .isEqualTo(DocumentIntentRouter.Intent.ANALYZE);
    }

    @Test
    void explicitPrefixesTakePriorityAndAreRemoved() {
        assertThat(router.route("分析：标题合理吗"))
                .isEqualTo(new DocumentIntentRouter.Decision(DocumentIntentRouter.Intent.ANALYZE, "标题合理吗"));
        assertThat(router.route("修改：把标题改成总结"))
                .isEqualTo(new DocumentIntentRouter.Decision(DocumentIntentRouter.Intent.EDIT, "把标题改成总结"));
        assertThat(router.route("生成：根据原文生成建议文案"))
                .isEqualTo(new DocumentIntentRouter.Decision(DocumentIntentRouter.Intent.GENERATE, "根据原文生成建议文案"));
    }
}
