package com.example.ykdsummer.fashion.persistence;

import com.example.ykdsummer.fashion.domain.FashionProduct;
import com.example.ykdsummer.fashion.domain.FashionProductDraft;
import com.example.ykdsummer.fashion.domain.FashionProductSearch;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC catalog implementation keeps supplier-facing data independent from each user's wardrobe. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class JdbcFashionCatalogRepository implements FashionCatalogRepository {
    private static final String PRODUCT_COLUMNS = """
            p.id, p.product_code, p.title, p.brand, p.category_code, p.sub_category_code, p.gender_target,
            p.color_primary, p.color_secondary_json, p.style_tags_json, p.season_tags_json, p.occasion_tags_json,
            p.material, p.fit_code, p.pattern_code, p.price, p.currency, p.availability_status, p.featured_rank,
            p.source, p.source_product_id, p.source_url, p.text_description, p.created_at, p.updated_at,
            (SELECT image_url FROM fashion_product_images pi
             WHERE pi.product_id = p.id AND pi.image_role = 'PRIMARY'
             ORDER BY pi.sort_order, pi.id LIMIT 1) AS primary_image_url
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcFashionCatalogRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public FashionProduct upsertProduct(FashionProductDraft draft) {
        ProductValues values = validate(draft);
        FashionProduct product = transactions.execute(status -> {
            Optional<Long> existingId = findId(values.productCode());
            long productId = existingId.orElseGet(() -> insert(values));
            if (existingId.isPresent()) update(productId, values);
            replacePrimaryImage(productId, values.primaryImageUrl());
            return findById(productId).orElseThrow(() -> new IllegalStateException("Could not read saved fashion product"));
        });
        if (product == null) throw new IllegalStateException("Could not save fashion product");
        return product;
    }

    @Override
    public List<FashionProduct> listCatalog(int limit) {
        return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM fashion_products p "
                        + "ORDER BY p.featured_rank DESC, p.updated_at DESC, p.id DESC LIMIT ?",
                (rs, row) -> product(rs), boundedLimit(limit, 100));
    }

    @Override
    public Optional<FashionProduct> findByProductCode(String productCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + PRODUCT_COLUMNS + " FROM fashion_products p WHERE p.product_code = ?",
                    (rs, row) -> product(rs), productCode(productCode)));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public List<FashionProduct> searchActive(FashionProductSearch search) {
        FashionProductSearch safeSearch = search == null
                ? new FashionProductSearch(null, null, null, null, null, null, null, null, 12) : search;
        validateRange(safeSearch.minPrice(), safeSearch.maxPrice());
        StringBuilder sql = new StringBuilder("SELECT ").append(PRODUCT_COLUMNS)
                .append(" FROM fashion_products p WHERE p.availability_status = 'ACTIVE'");
        List<Object> arguments = new ArrayList<>();
        appendCategory(sql, arguments, safeSearch.categoryCode());
        appendEquals(sql, arguments, "p.color_primary", normalizedText(safeSearch.color(), 64));
        appendJsonTag(sql, arguments, "p.style_tags_json", normalizedText(safeSearch.styleTag(), 128));
        appendJsonTag(sql, arguments, "p.season_tags_json", normalizedText(safeSearch.seasonTag(), 128));
        appendJsonTag(sql, arguments, "p.occasion_tags_json", normalizedText(safeSearch.occasionTag(), 128));
        if (safeSearch.minPrice() != null) {
            sql.append(" AND p.price >= ?");
            arguments.add(safeSearch.minPrice());
        }
        if (safeSearch.maxPrice() != null) {
            sql.append(" AND p.price <= ?");
            arguments.add(safeSearch.maxPrice());
        }
        String keyword = normalizedText(safeSearch.keyword(), 128);
        if (!keyword.isBlank()) {
            sql.append(" AND (p.title LIKE ? OR p.brand LIKE ? OR p.text_description LIKE ?)");
            String pattern = "%" + keyword + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        sql.append(" ORDER BY p.featured_rank DESC, p.price ASC, p.id DESC LIMIT ?");
        arguments.add(boundedLimit(safeSearch.limit(), 12, 1, 20));
        return jdbc.query(sql.toString(), (rs, row) -> product(rs), arguments.toArray());
    }

    @Override
    public boolean updateAvailability(long productId, String availabilityStatus) {
        String status = oneOf(availabilityStatus, "availabilityStatus", "DRAFT", "ACTIVE", "OUT_OF_STOCK", "ARCHIVED");
        return jdbc.update("UPDATE fashion_products SET availability_status = ? WHERE id = ?", status, productId) == 1;
    }

    private long insert(ProductValues values) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO fashion_products(
                        product_code, title, brand, category_code, sub_category_code, gender_target, color_primary,
                        color_secondary_json, style_tags_json, season_tags_json, occasion_tags_json, material, fit_code,
                        pattern_code, price, currency, availability_status, featured_rank, source, source_product_id,
                        source_url, text_description)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindProduct(statement, values);
            return statement;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("Could not create fashion product");
        return id.longValue();
    }

    private void update(long productId, ProductValues values) {
        jdbc.update("""
                UPDATE fashion_products
                SET title = ?, brand = ?, category_code = ?, sub_category_code = ?, gender_target = ?, color_primary = ?,
                    color_secondary_json = ?, style_tags_json = ?, season_tags_json = ?, occasion_tags_json = ?, material = ?,
                    fit_code = ?, pattern_code = ?, price = ?, currency = ?, availability_status = ?, featured_rank = ?,
                    source = ?, source_product_id = ?, source_url = ?, text_description = ?
                WHERE id = ?
                """, values.title(), values.brand(), values.categoryCode(), values.subCategoryCode(), values.genderTarget(),
                values.colorPrimary(), values.secondaryColorsJson(), values.styleTagsJson(), values.seasonTagsJson(),
                values.occasionTagsJson(), values.material(), values.fitCode(), values.patternCode(), values.price(),
                values.currency(), values.availabilityStatus(), values.featuredRank(), values.source(), values.sourceProductId(),
                values.sourceUrl(), values.textDescription(), productId);
    }

    private void bindProduct(PreparedStatement statement, ProductValues values) throws java.sql.SQLException {
        statement.setString(1, values.productCode());
        statement.setString(2, values.title());
        statement.setString(3, values.brand());
        statement.setString(4, values.categoryCode());
        statement.setString(5, values.subCategoryCode());
        statement.setString(6, values.genderTarget());
        statement.setString(7, values.colorPrimary());
        statement.setString(8, values.secondaryColorsJson());
        statement.setString(9, values.styleTagsJson());
        statement.setString(10, values.seasonTagsJson());
        statement.setString(11, values.occasionTagsJson());
        statement.setString(12, values.material());
        statement.setString(13, values.fitCode());
        statement.setString(14, values.patternCode());
        statement.setBigDecimal(15, values.price());
        statement.setString(16, values.currency());
        statement.setString(17, values.availabilityStatus());
        statement.setInt(18, values.featuredRank());
        statement.setString(19, values.source());
        statement.setString(20, values.sourceProductId());
        statement.setString(21, values.sourceUrl());
        statement.setString(22, values.textDescription());
    }

    private void replacePrimaryImage(long productId, String imageUrl) {
        if (imageUrl.isBlank()) {
            jdbc.update("DELETE FROM fashion_product_images WHERE product_id = ? AND image_role = 'PRIMARY'", productId);
            return;
        }
        jdbc.update("""
                INSERT INTO fashion_product_images(product_id, image_url, image_role, sort_order)
                VALUES (?, ?, 'PRIMARY', 0)
                ON DUPLICATE KEY UPDATE image_url = VALUES(image_url), sort_order = VALUES(sort_order)
                """, productId, imageUrl);
    }

    private Optional<Long> findId(String productCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT id FROM fashion_products WHERE product_code = ?", Long.class, productCode));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Optional<FashionProduct> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + PRODUCT_COLUMNS + " FROM fashion_products p WHERE p.id = ?",
                    (rs, row) -> product(rs), id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private ProductValues validate(FashionProductDraft draft) {
        if (draft == null) throw new IllegalArgumentException("product is required");
        String category = code(draft.categoryCode(), 64, "categoryCode");
        String subCategory = code(draft.subCategoryCode(), 64, "subCategoryCode");
        requireTaxonomy(category);
        requireSubCategory(category, subCategory);
        BigDecimal price = draft.price();
        if (price == null || price.signum() < 0) throw new IllegalArgumentException("price must be zero or greater");
        return new ProductValues(
                productCode(draft.productCode()), requiredText(draft.title(), 255, "title"), normalizedText(draft.brand(), 128),
                category, subCategory, oneOf(defaulted(draft.genderTarget(), "UNISEX"), "genderTarget", "UNISEX", "FEMALE", "MALE"),
                normalizedText(draft.colorPrimary(), 64), jsonArray(draft.secondaryColors()), jsonArray(draft.styleTags()),
                jsonArray(draft.seasonTags()), jsonArray(draft.occasionTags()), normalizedText(draft.material(), 128),
                normalizedText(draft.fitCode(), 64), normalizedText(draft.patternCode(), 64), price,
                oneOf(defaulted(draft.currency(), "CNY"), "currency", "CNY"),
                oneOf(defaulted(draft.availabilityStatus(), "DRAFT"), "availabilityStatus", "DRAFT", "ACTIVE", "OUT_OF_STOCK", "ARCHIVED"),
                Math.max(0, Math.min(10_000, draft.featuredRank())),
                code(defaulted(draft.source(), "ADMIN_CATALOG"), 32, "source"), normalizedText(draft.sourceProductId(), 128),
                normalizedText(draft.sourceUrl(), 2048), requiredText(draft.textDescription(), 20_000, "textDescription"),
                normalizedText(draft.primaryImageUrl(), 2048));
    }

    private void requireTaxonomy(String categoryCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fashion_taxonomy_nodes WHERE code = ? AND status = 'ACTIVE'",
                Integer.class, categoryCode);
        if (count == null || count != 1) throw new IllegalArgumentException("Unknown Fashion taxonomy code: " + categoryCode);
    }

    private void requireSubCategory(String categoryCode, String subCategoryCode) {
        String parent = jdbc.queryForObject("SELECT parent_code FROM fashion_taxonomy_nodes WHERE code = ? AND status = 'ACTIVE'",
                String.class, subCategoryCode);
        if (parent == null && categoryCode.equals(subCategoryCode)) return;
        if (!categoryCode.equals(parent)) {
            throw new IllegalArgumentException("subCategoryCode must belong to categoryCode");
        }
    }

    private FashionProduct product(ResultSet rs) throws java.sql.SQLException {
        return new FashionProduct(rs.getLong("id"), rs.getString("product_code"), rs.getString("title"), rs.getString("brand"),
                rs.getString("category_code"), rs.getString("sub_category_code"), rs.getString("gender_target"),
                rs.getString("color_primary"), stringList(rs.getString("color_secondary_json")),
                stringList(rs.getString("style_tags_json")), stringList(rs.getString("season_tags_json")),
                stringList(rs.getString("occasion_tags_json")), rs.getString("material"), rs.getString("fit_code"),
                rs.getString("pattern_code"), rs.getBigDecimal("price"), rs.getString("currency"),
                rs.getString("availability_status"), rs.getInt("featured_rank"), rs.getString("source"),
                rs.getString("source_product_id"), rs.getString("source_url"), rs.getString("text_description"),
                rs.getString("primary_image_url"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private void appendCategory(StringBuilder sql, List<Object> arguments, String category) {
        String code = normalizedCode(category);
        if (code.isBlank()) return;
        sql.append(" AND (p.category_code = ? OR p.sub_category_code = ?)");
        arguments.add(code);
        arguments.add(code);
    }

    private static void appendEquals(StringBuilder sql, List<Object> arguments, String column, String value) {
        if (value.isBlank()) return;
        sql.append(" AND ").append(column).append(" = ?");
        arguments.add(value);
    }

    private static void appendJsonTag(StringBuilder sql, List<Object> arguments, String column, String value) {
        if (value.isBlank()) return;
        sql.append(" AND JSON_CONTAINS(").append(column).append(", JSON_QUOTE(?))");
        arguments.add(value);
    }

    private String jsonArray(List<String> values) {
        List<String> source = values == null ? List.of() : values;
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(source.stream()
                .map(value -> normalizedText(value, 128)).filter(value -> !value.isBlank()).toList()));
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Fashion product tags", exception);
        }
    }

    private List<String> stringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(value -> { if (value.isTextual()) values.add(value.asText()); });
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static String productCode(String value) {
        String candidate = normalizedText(value, 64).toUpperCase(Locale.ROOT);
        if (candidate.isBlank() || !candidate.matches("[A-Z0-9][A-Z0-9_-]*")) {
            throw new IllegalArgumentException("productCode must contain only uppercase letters, digits, underscores or hyphens");
        }
        return candidate;
    }

    private static String code(String value, int limit, String field) {
        String candidate = normalizedText(value, limit).toUpperCase(Locale.ROOT);
        if (candidate.isBlank() || !candidate.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(field + " must be an uppercase underscore code");
        }
        return candidate;
    }

    private static String normalizedCode(String value) {
        String candidate = normalizedText(value, 64).toUpperCase(Locale.ROOT);
        return candidate.matches("[A-Z0-9_]+") ? candidate : "";
    }

    private static String oneOf(String value, String field, String... allowed) {
        String candidate = code(value, 32, field);
        for (String option : allowed) if (option.equals(candidate)) return candidate;
        throw new IllegalArgumentException("Unsupported " + field);
    }

    private static String requiredText(String value, int limit, String field) {
        String candidate = normalizedText(value, limit);
        if (candidate.isBlank()) throw new IllegalArgumentException(field + " is required");
        return candidate;
    }

    private static String normalizedText(String value, int limit) {
        String candidate = value == null ? "" : value.replace('\u0000', ' ').strip();
        return candidate.length() <= limit ? candidate : candidate.substring(0, limit);
    }

    private static String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void validateRange(BigDecimal min, BigDecimal max) {
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0) {
            throw new IllegalArgumentException("price filters cannot be negative");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("minPrice cannot exceed maxPrice");
        }
    }

    private static int boundedLimit(int value, int fallback) { return boundedLimit(value, fallback, 1, 100); }
    private static int boundedLimit(Integer value, int fallback, int min, int max) {
        int candidate = value == null ? fallback : value;
        return Math.max(min, Math.min(max, candidate));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private record ProductValues(
            String productCode, String title, String brand, String categoryCode, String subCategoryCode, String genderTarget,
            String colorPrimary, String secondaryColorsJson, String styleTagsJson, String seasonTagsJson, String occasionTagsJson,
            String material, String fitCode, String patternCode, BigDecimal price, String currency, String availabilityStatus,
            int featuredRank, String source, String sourceProductId, String sourceUrl, String textDescription, String primaryImageUrl
    ) { }
}
