package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LocalImageAssetStoreTest {

    @Test
    void isolatesNewImagesAndKeepsRevisionHistoryAcrossStoreRestart() throws Exception {
        Path root = Files.createTempDirectory("image-assets");
        LocalImageAssetStore store = new LocalImageAssetStore(root);

        var cat = store.saveGenerated("user-a", "一只橘猫", new byte[]{1, 2}, "https://example/cat");
        var dog = store.saveGenerated("user-a", "一只小狗", new byte[]{3, 4}, "https://example/dog");
        var catV2 = store.saveRevision("user-a", cat.assetId(), "把橘猫改成白猫", new byte[]{5, 6}, null);
        var annotated = store.annotate("user-a", cat.assetId(), "一只白猫坐在木桌上").orElseThrow();

        assertThat(cat.assetId()).isNotEqualTo(dog.assetId());
        assertThat(catV2.assetId()).isEqualTo(cat.assetId());
        assertThat(catV2.version()).isEqualTo(2);
        assertThat(annotated.tags()).contains("白猫", "木桌");
        assertThat(store.current("user-a")).contains(annotated);
        assertThat(store.recent("user-a", 8)).extracting(LocalImageAssetStore.StoredImage::assetId)
                .contains(cat.assetId(), dog.assetId());

        LocalImageAssetStore restarted = new LocalImageAssetStore(root);
        assertThat(restarted.current("user-a")).isPresent();
        assertThat(restarted.current("user-a").orElseThrow().assetId()).isEqualTo(cat.assetId());
        assertThat(restarted.find("user-a", cat.assetId(), 1)).isPresent();
        assertThat(restarted.latest("user-a", cat.assetId()).orElseThrow().tags()).contains("白猫");
    }

    @Test
    void clearsOnlyTheCurrentPointerAndKeepsImageVersions() throws Exception {
        Path root = Files.createTempDirectory("image-assets");
        LocalImageAssetStore store = new LocalImageAssetStore(root);
        var saved = store.saveGenerated("user-a", "一只猫", new byte[]{1, 2, 3}, null);

        assertThat(store.clearCurrent("user-a")).isTrue();
        assertThat(store.current("user-a")).isEmpty();
        assertThat(store.find("user-a", saved.assetId(), saved.version())).contains(saved);
        assertThat(new LocalImageAssetStore(root).current("user-a")).isEmpty();
    }
}
