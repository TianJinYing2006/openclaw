package com.wechatbot.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechatbot.fashion.wardrobe.domain.SemanticReferenceMatch;
import com.wechatbot.fashion.wardrobe.domain.WardrobeSearchCriteria;
import com.wechatbot.fashion.wardrobe.persistence.FashionReferenceRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in local probe: three annotated images -> OSS -> MySQL -> Qdrant -> semantic query. */
@EnabledIfEnvironmentVariable(named = "FASHION_REFERENCE_LIVE", matches = "true")
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.persistence.enabled=true",
                "app.persistence.redis.enabled=false",
                "app.admin.enabled=false",
                "app.fashion.semantic.enabled=true",
                "app.fashion.reference.enabled=true",
                "app.fashion.reference.import-enabled=false",
                "app.fashion.semantic.dispatch-interval=1s"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class FashionReferenceLiveIntegrationTest {
    private static final Path ANNOTATIONS = Path.of("D:/创意/穿搭图片标准化标注_1.0.0_前3张.json");
    private static final Path IMAGES = Path.of("D:/创意");

    @Autowired private FashionReferenceImportService importer;
    @Autowired private FashionReferenceRepository repository;
    @Autowired private FashionReferenceIndexService indexing;
    @Autowired private FashionReferenceSemanticSearchService search;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void importsThreeRealLooksAndRetrievesOnlyThePublicScope() throws Exception {
        var report = importer.importFile(ANNOTATIONS, IMAGES, 3, true);

        assertThat(report.failures()).isEmpty();
        assertThat(report.imported()).isEqualTo(3);
        List<String> codes = List.of("02_Nanase_格纹衬衫阔腿裤.webp", "03_Nanase_灰色针织阔腿裤.webp",
                "04_Nanase_米色针织背心阔腿裤.webp");
        assertThat(codes).allSatisfy(code -> assertThat(repository.findByReferenceCode(code))
                .get().satisfies(look -> {
                    assertThat(look.status()).isEqualTo("ACTIVE");
                    assertThat(look.garments()).hasSize(3);
                    assertThat(look.usageRights()).isEqualTo("LOCAL_DEVELOPMENT_ONLY");
                }));

        awaitIndexed(codes, Duration.ofSeconds(90));
        WardrobeSearchCriteria noFilters = WardrobeSearchCriteria.from(
                null, null, List.of(), null, null, List.of(), List.of(), null);
        List<SemanticReferenceMatch> matches = search.search("简约通勤的灰色针织上衣和宽松裤子", noFilters, 3);

        assertThat(matches).isNotEmpty();
        assertThat(matches).allSatisfy(match -> {
            assertThat(match.score()).isGreaterThanOrEqualTo(0d);
            assertThat(codes).contains(match.look().referenceCode());
            assertThat(match.look().status()).isEqualTo("ACTIVE");
        });
    }

    private void awaitIndexed(List<String> codes, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (var job : indexing.claimWork()) indexing.execute(job);
            List<String> statuses = jdbc.query("""
                    SELECT job.status FROM fashion_reference_semantic_index_jobs job
                    JOIN fashion_reference_looks look ON look.id = job.reference_look_id
                    WHERE look.reference_code IN (?, ?, ?)
                    """, (rs, row) -> rs.getString("status"), codes.get(0), codes.get(1), codes.get(2));
            if (statuses.size() == 3 && statuses.stream().allMatch("SUCCEEDED"::equals)) return;
            if (statuses.stream().anyMatch("FAILED"::equals)) throw new AssertionError("Public semantic index job failed");
            Thread.sleep(500L);
        }
        throw new AssertionError("Public semantic index jobs did not finish within " + timeout);
    }
}
