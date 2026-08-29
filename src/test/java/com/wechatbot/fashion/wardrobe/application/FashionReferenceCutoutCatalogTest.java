package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechatbot.fashion.wardrobe.config.FashionReferenceImportOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FashionReferenceCutoutCatalogTest {
    @TempDir Path directory;

    @Test
    void existingOutputWinsOverAStalePendingJobState() throws Exception {
        Path outputs = Files.createDirectory(directory.resolve("outputs"));
        Files.write(outputs.resolve("wet_001_1_top.png"), new byte[]{1, 2, 3});
        Path jobs = directory.resolve("jobs.jsonl");
        Files.writeString(jobs, String.join(System.lineSeparator(),
                "{\"jobId\":\"wet_001_1_top\",\"imageId\":\"look.webp\",\"itemIndex\":1,\"category\":\"TOP\","
                        + "\"outputPath\":\"D:/old/cleaned/images/wet_001_1_top.png\",\"status\":\"PENDING\"}",
                "{\"jobId\":\"wet_001_2_bottom\",\"imageId\":\"look.webp\",\"itemIndex\":2,\"category\":\"BOTTOM\","
                        + "\"outputPath\":\"D:/old/cleaned/images/wet_001_2_bottom.png\",\"status\":\"FAILED\","
                        + "\"error\":\"provider failed\"}"));
        FashionReferenceImportOptions options = new FashionReferenceImportOptions(
                Set.of("TOP", "BOTTOM"), jobs, null, outputs);

        FashionReferenceCutoutCatalog catalog = FashionReferenceCutoutCatalog.load(new ObjectMapper(), options);

        assertThat(catalog.find("look.webp", 1).status()).isEqualTo("READY");
        assertThat(catalog.find("look.webp", 1).outputFile()).isEqualTo(outputs.resolve("wet_001_1_top.png"));
        assertThat(catalog.find("look.webp", 2).status()).isEqualTo("FAILED");
        assertThat(catalog.find("look.webp", 2).outputFile()).isNull();
    }

    @Test
    void derivesTheStableWetFileNameWhenCompletedJobsWereRemovedFromTheQueue() throws Exception {
        Path outputs = Files.createDirectory(directory.resolve("derived-outputs"));
        Files.write(outputs.resolve("wet_012_2_bottom.png"), new byte[]{8, 9});
        FashionReferenceImportOptions options = new FashionReferenceImportOptions(
                Set.of("TOP", "BOTTOM"), null, null, outputs);

        var entry = FashionReferenceCutoutCatalog.load(new ObjectMapper(), options)
                .find("13_look.webp", 2, 12, "BOTTOM");

        assertThat(entry.status()).isEqualTo("READY");
        assertThat(entry.jobId()).isEqualTo("wet_012_2_bottom");
        assertThat(entry.outputFile()).isEqualTo(outputs.resolve("wet_012_2_bottom.png"));
    }
}
