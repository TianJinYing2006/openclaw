package com.example.ykdsummer.bot.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class LocalDocumentAssetStoreTest {

    @Test
    void revisionAndRestoreAlwaysAppendANewVersion() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("document-assets"));
        var v1 = store.create("user-a", "周报", "txt", "第一版".getBytes(StandardCharsets.UTF_8), "初稿");
        var v2 = store.revise("user-a", v1.assetId(), "周报", "txt", "第二版".getBytes(StandardCharsets.UTF_8), "补充进度");
        var v3 = store.restore("user-a", v1.assetId(), 1);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(new String(store.readBytes(v3), StandardCharsets.UTF_8)).isEqualTo("第一版");
        assertThat(store.current("user-a")).contains(v3);
        assertThat(store.versions("user-a", v1.assetId())).extracting(LocalDocumentAssetStore.StoredDocument::version)
                .containsExactly(1, 2, 3);
    }

    @Test
    void formatConversionReplacesTheOldKnownExtensionInsteadOfAppendingIt() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("document-assets"));
        var v1 = store.create("user-a", "杭州出行建议.docx", "docx", new byte[]{1}, "原 Word");
        var v2 = store.revise("user-a", v1.assetId(), v1.fileName(), "pdf", new byte[]{2}, "转 PDF");

        assertThat(v1.fileName()).isEqualTo("杭州出行建议.docx");
        assertThat(v2.fileName()).isEqualTo("杭州出行建议.pdf");
        assertThat(v2.fileName()).doesNotContain(".docx.pdf");
    }

    @Test
    void clearsOnlyTheCurrentPointerAndKeepsDocumentVersions() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("document-assets"));
        var saved = store.create("user-a", "周报", "txt", "内容".getBytes(StandardCharsets.UTF_8), "初稿");

        assertThat(store.clearCurrent("user-a")).isTrue();
        assertThat(store.current("user-a")).isEmpty();
        assertThat(store.find("user-a", saved.assetId(), saved.version())).contains(saved);
    }
}
