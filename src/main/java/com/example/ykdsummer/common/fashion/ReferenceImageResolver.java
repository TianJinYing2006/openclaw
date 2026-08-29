package com.example.ykdsummer.common.fashion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考穿搭图片 URL 解析器（中性共用层）。
 *
 * <p>读取 {@code scripts/upload_images_to_oss.py} 生成的 {@code data/image_urls.json}，
 * 按 outfit 编号（如 002）返回整套原图 + 分割单品图片的 URL 列表。
 * 文件不存在时返回空列表，不阻断主流程。
 *
 * <p>本类位于 {@code common.fashion} 共用包，不依赖 {@code ai.fashion}（Look 引擎）也不依赖
 * {@code fashion} / {@code fashion.wardrobe}（衣橱引擎）。两套 fashion 子系统都依赖本包，
 * 从而把原先 {@code fashion → ai.fashion} 的反向依赖收敛为 {@code fashion → common.fashion}，
 * 符合「衣橱引擎禁止依赖 Look 引擎」的边界契约（见 docs/architecture/FASHION_BOUNDARIES.md）。
 */
@Component
public class ReferenceImageResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageResolver.class);
    private static final String IMAGE_URLS_PATH = "data/image_urls.json";

    private final ObjectMapper objectMapper;
    private volatile Map<String, OutfitImages> byId = Map.of();

    /** 单套穿搭的图片：overview 为整套原图，garments 为分割单品列表。 */
    public record OutfitImages(List<GarmentImage> garments, String overview) {}

    public record GarmentImage(String garment, String file, String url) {}

    public ReferenceImageResolver() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 启动时加载映射；文件缺失仅记日志（图片发送自动跳过）。 */
    @jakarta.annotation.PostConstruct
    public void load() {
        Path path = Paths.get(IMAGE_URLS_PATH);
        if (!Files.exists(path)) {
            log.warn("Reference image url map not found: {} (run scripts/upload_images_to_oss.py first)", IMAGE_URLS_PATH);
            return;
        }
        try {
            Map<String, OutfitImages> loaded = objectMapper.readValue(
                    Files.readString(path), new TypeReference<LinkedHashMap<String, OutfitImages>>() {});
            this.byId = loaded == null ? Map.of() : loaded;
            log.info("Loaded {} outfit image mappings from {}", byId.size(), IMAGE_URLS_PATH);
        } catch (IOException e) {
            log.error("Failed to load reference image url map {}: {}", IMAGE_URLS_PATH, e.getMessage());
        }
    }

    /**
     * 返回指定 outfit 编号的图片 URL，顺序：整套原图在前，分割单品在后。
     * 未知编号返回空列表。
     */
    public List<String> urlsFor(String outfitId) {
        if (outfitId == null || outfitId.isBlank()) {
            return List.of();
        }
        OutfitImages images = byId.get(outfitId);
        if (images == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        if (images.overview() != null && !images.overview().isBlank()) {
            urls.add(images.overview());
        }
        if (images.garments() != null) {
            for (GarmentImage garment : images.garments()) {
                if (garment != null && garment.url() != null && !garment.url().isBlank()) {
                    urls.add(garment.url());
                }
            }
        }
        return Collections.unmodifiableList(urls);
    }

    /**
     * 返回指定 outfit 的分割单品图列表（不含整套原图）；未知编号返回空列表。
     * 调用方可按 {@link GarmentImage#garment()}（如 top/bottom）挑选目标单品。
     */
    public List<GarmentImage> garmentsFor(String outfitId) {
        if (outfitId == null || outfitId.isBlank()) {
            return List.of();
        }
        OutfitImages images = byId.get(outfitId);
        if (images == null || images.garments() == null) {
            return List.of();
        }
        List<GarmentImage> garments = images.garments().stream()
                .filter(garment -> garment != null && garment.url() != null && !garment.url().isBlank())
                .toList();
        return Collections.unmodifiableList(garments);
    }
}
