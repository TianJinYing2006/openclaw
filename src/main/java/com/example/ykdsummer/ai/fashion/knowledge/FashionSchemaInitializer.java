package com.example.ykdsummer.ai.fashion.knowledge;

import com.example.ykdsummer.ai.fashion.model.SeedEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 穿搭知识库初始化器。
 *
 * <p>启动时：
 * <ol>
 *   <li>创建 SQLite FTS5 虚拟表用于全文检索</li>
 *   <li>从 data/xiaohongshu_fashion_seed.json 加载种子数据</li>
 *   <li>将种子数据写入 FTS5 索引</li>
 * </ol>
 *
 * <p>幂等设计：每次启动先清空再导入，保证数据一致性。
 */
@Component
public class FashionSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(FashionSchemaInitializer.class);
    private static final String SEED_DATA_PATH = "data/xiaohongshu_fashion_seed.json";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public FashionSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void initialize() {
        // trigram 分词器不支持重建，每次启动先 DROP 再 CREATE，保证结构一致
        dropFts5TableIfExists();
        createFts5Table();
        loadSeedData();
    }

    /**
     * 删除旧 FTS5 表。tokenizer 变更时（如 unicode61 → trigram）必须重建，
     * 否则 CREATE VIRTUAL TABLE IF NOT EXISTS 会复用旧结构。
     */
    private void dropFts5TableIfExists() {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS fashion_seed_fts");
        } catch (Exception e) {
            log.error("Failed to drop FTS5 table: {}", e.getMessage());
        }
    }

    /**
     * 创建 FTS5 虚拟表。使用 trigram 分词器，支持中文子词检索
     * （如查询"短袖"可命中"韩系宽松短袖T恤"）。
     */
    private void createFts5Table() {
        try {
            jdbcTemplate.execute("""
                    CREATE VIRTUAL TABLE fashion_seed_fts USING fts5(
                        id UNINDEXED,
                        content,
                        source UNINDEXED,
                        summary UNINDEXED,
                        top UNINDEXED,
                        bottom UNINDEXED,
                        shoes UNINDEXED,
                        accessories UNINDEXED,
                        style,
                        scene,
                        season,
                        color_scheme,
                        tokenize='trigram'
                    )
                    """);
            log.info("FTS5 table 'fashion_seed_fts' created (trigram tokenizer)");
        } catch (Exception e) {
            log.error("Failed to create FTS5 table: {}", e.getMessage());
        }
    }

    /**
     * 从 JSON 文件加载种子数据并写入 FTS5 索引。
     */
    private void loadSeedData() {
        Path seedPath = Paths.get(SEED_DATA_PATH);
        if (!Files.exists(seedPath)) {
            log.warn("Seed data file not found: {}. RAG retrieval will be empty.", SEED_DATA_PATH);
            return;
        }

        try {
            String json = Files.readString(seedPath);
            List<SeedEntry> entries = objectMapper.readValue(json, new TypeReference<>() {});

            // 清空旧数据
            jdbcTemplate.execute("DELETE FROM fashion_seed_fts");

            // 批量插入
            for (SeedEntry entry : entries) {
                insertEntry(entry);
            }

            log.info("Loaded {} seed entries into FTS5 index", entries.size());

        } catch (IOException e) {
            log.error("Failed to read seed data file: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to load seed data: {}", e.getMessage());
        }
    }

    private void insertEntry(SeedEntry entry) {
        String top = entry.outfit() != null ? safeStr(entry.outfit().top()) : "";
        String bottom = entry.outfit() != null ? safeStr(entry.outfit().bottom()) : "";
        String shoes = entry.outfit() != null ? safeStr(entry.outfit().shoes()) : "";
        String accessories = entry.outfit() != null ? safeStr(entry.outfit().accessories()) : "";
        String style = entry.tags() != null && entry.tags().style() != null
                ? String.join(" ", entry.tags().style()) : "";
        String scene = entry.tags() != null && entry.tags().scene() != null
                ? String.join(" ", entry.tags().scene()) : "";
        String season = entry.tags() != null && entry.tags().season() != null
                ? String.join(" ", entry.tags().season()) : "";
        String colorScheme = entry.tags() != null ? safeStr(entry.tags().colorScheme()) : "";

        // content 字段拼接所有可搜索文本
        String content = entry.searchableText();

        jdbcTemplate.update("""
                INSERT INTO fashion_seed_fts
                (id, content, source, summary, top, bottom, shoes, accessories,
                 style, scene, season, color_scheme)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                safeStr(entry.id()), content, safeStr(entry.source()),
                safeStr(entry.summary()), top, bottom, shoes, accessories,
                style, scene, season, colorScheme);
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }
}
