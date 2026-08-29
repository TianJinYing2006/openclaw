package com.wechatbot.fashion.wardrobe.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wechatbot.fashion.wardrobe.domain.FashionProduct;
import com.wechatbot.fashion.wardrobe.domain.FashionProductDraft;
import com.wechatbot.fashion.wardrobe.domain.FashionProductSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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

/** Explicit opt-in local MySQL verification for V8 catalog storage and search. */
@EnabledIfEnvironmentVariable(named = "PERSISTENCE_INTEGRATION", matches = "true")
class FashionCatalogRepositoryIntegrationTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private FashionCatalogRepository repository;
    private String productCode;

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
        repository = new JdbcFashionCatalogRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        productCode = "TEST-CATALOG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && productCode != null) jdbc.update("DELETE FROM fashion_products WHERE product_code = ?", productCode);
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsAndSearchesCatalogProductsWithoutExposingOutOfStockItems() {
        assertTrue(repository.searchActive(new FashionProductSearch(null, "T_SHIRT", null, null, null, null,
                null, null, 10)).stream().anyMatch(product -> product.productCode().equals("UNX-TEE-WHITE")));

        FashionProduct saved = repository.upsertProduct(new FashionProductDraft(
                productCode, "测试黑色基础 T 恤", "Test Brand", "TOP", "T_SHIRT", "UNISEX", "黑色", List.of(),
                List.of("简约", "通勤"), List.of("夏季"), List.of("通勤"), "棉", "常规", "纯色",
                new BigDecimal("199"), "CNY", "ACTIVE", 888, "ADMIN_CATALOG", "", "", "测试商品描述",
                "https://example.test/catalog.png"));

        assertEquals(productCode, saved.productCode());
        assertEquals("https://example.test/catalog.png", saved.primaryImageUrl());
        assertTrue(repository.searchActive(new FashionProductSearch("测试", "T_SHIRT", "黑色", "简约", "夏季", "通勤",
                new BigDecimal("100"), new BigDecimal("300"), 10)).stream()
                .anyMatch(product -> product.productCode().equals(productCode)));

        assertTrue(repository.updateAvailability(saved.id(), "OUT_OF_STOCK"));
        assertFalse(repository.searchActive(new FashionProductSearch(null, "T_SHIRT", null, null, null, null,
                null, null, 100)).stream().anyMatch(product -> product.productCode().equals(productCode)));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for integration tests");
        return value;
    }
}
