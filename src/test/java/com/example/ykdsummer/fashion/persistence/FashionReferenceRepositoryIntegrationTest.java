package com.example.ykdsummer.fashion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ykdsummer.fashion.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in verification for V15, the public reference model and its semantic outbox. */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class FashionReferenceRepositoryIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private FashionReferenceRepository repository;
    private String referenceCode;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("PERSISTENCE_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/ykd_summer?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"));
        config.setUsername(System.getenv().getOrDefault("PERSISTENCE_USERNAME", "root"));
        config.setPassword(requiredEnvironment("PERSISTENCE_PASSWORD"));
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcFashionReferenceRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        referenceCode = "reference-integration-" + UUID.randomUUID() + ".png";
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && referenceCode != null) {
            jdbc.update("DELETE FROM fashion_reference_looks WHERE reference_code IN (?, ?)",
                    referenceCode, referenceCode + "-duplicate");
        }
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsLookGarmentsAndOneRecoverableIndexJob() {
        String hash = sha256Text();
        FashionReferenceLook saved = repository.upsert(look(referenceCode, hash, "ACTIVE", List.of(
                garment(1, "浅灰色宽松短袖T恤", "TOP", "T_SHIRT", "LIGHT_GRAY"),
                garment(2, "深蓝色直筒牛仔裤", "BOTTOM", "JEANS", "DENIM_BLUE"))));

        assertThat(saved.id()).isPositive();
        assertThat(saved.garments()).extracting(FashionReferenceGarment::displayName)
                .containsExactly("浅灰色宽松短袖T恤", "深蓝色直筒牛仔裤");
        assertThat(repository.findBySha256(hash)).get().extracting(FashionReferenceLook::referenceCode)
                .isEqualTo(referenceCode);
        assertThat(repository.activeLooks(10)).extracting(FashionReferenceLook::id).contains(saved.id());
        assertThat(jdbc.queryForObject("SELECT status FROM fashion_reference_semantic_index_jobs WHERE reference_look_id = ?",
                String.class, saved.id())).isEqualTo("PENDING");

        FashionReferenceLook updated = repository.upsert(look(referenceCode, hash, "ACTIVE", List.of(
                garment(1, "浅灰色短袖T恤", "TOP", "T_SHIRT", "LIGHT_GRAY"))));
        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(updated.garments()).singleElement().extracting(FashionReferenceGarment::displayName)
                .isEqualTo("浅灰色短袖T恤");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fashion_reference_semantic_index_jobs WHERE reference_look_id = ?",
                Integer.class, saved.id())).isEqualTo(1);

        assertThatThrownBy(() -> repository.upsert(look(referenceCode + "-duplicate", hash, "ACTIVE",
                List.of(garment(1, "重复图片", "TOP", "T_SHIRT", "GRAY")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    private static FashionReferenceLook look(String code, String hash, String status,
            List<FashionReferenceGarment> garments) {
        return new FashionReferenceLook(0L, code, "夏季简约穿搭", "img_public1234", 1, "image/png",
                code, "", "INTEGRATION_TEST", "LOCAL_DEVELOPMENT_ONLY", hash, "", "1.0.0", "{}",
                status, garments, Instant.now(), Instant.now());
    }

    private static FashionReferenceGarment garment(int index, String name, String parent, String category,
            String color) {
        return new FashionReferenceGarment(0L, 0L, index, name, parent, category, "UNISEX", color,
                List.of(), List.of(), List.of("CASUAL", "MINIMAL"), "RELAXED", "SOLID", "H_LINE",
                "REGULAR", List.of("COTTON"), List.of("SUMMER"), List.of("DAILY"), 1, "FULL",
                new BigDecimal("0.95"), new BigDecimal("0.92"), "{}");
    }

    private static String sha256Text() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }
}
