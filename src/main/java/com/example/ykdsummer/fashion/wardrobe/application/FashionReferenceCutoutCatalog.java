package com.example.ykdsummer.fashion.wardrobe.application;

import com.example.ykdsummer.fashion.wardrobe.config.FashionReferenceImportOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Rebuilds single-garment output state from JSONL metadata and the files that actually exist. */
final class FashionReferenceCutoutCatalog {
    private final Map<Key, Entry> entries;
    private final Path outputDirectory;

    private FashionReferenceCutoutCatalog(Map<Key, Entry> entries, Path outputDirectory) {
        this.entries = Map.copyOf(entries);
        this.outputDirectory = outputDirectory;
    }

    static FashionReferenceCutoutCatalog load(ObjectMapper objectMapper, FashionReferenceImportOptions options) {
        FashionReferenceImportOptions effective = options == null
                ? FashionReferenceImportOptions.defaults() : options;
        Map<Key, Entry> entries = new LinkedHashMap<>();
        readJsonLines(objectMapper, effective.cutoutJobFile(), entries);
        readJsonLines(objectMapper, effective.cutoutManifestFile(), entries);
        return new FashionReferenceCutoutCatalog(entries, effective.cutoutImageDirectory());
    }

    Entry find(String imageId, int itemIndex) {
        return find(imageId, itemIndex, 0, "");
    }

    Entry find(String imageId, int itemIndex, int sourceOrdinal, String category) {
        int boundedItemIndex = Math.max(1, itemIndex);
        Entry entry = entries.get(new Key(safe(imageId), boundedItemIndex));
        if (entry == null) entry = derived(sourceOrdinal, boundedItemIndex, category);
        Optional<Path> actualFile = actualFile(entry);
        if (actualFile.isPresent()) return entry.ready(actualFile.get());
        return entry.withoutFile();
    }

    private static Entry derived(int sourceOrdinal, int itemIndex, String category) {
        if (sourceOrdinal < 1) return Entry.pending();
        String normalizedCategory = safe(category).toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        if (!normalizedCategory.matches("[a-z0-9_]+")) return Entry.pending();
        String jobId = "wet_%03d_%d_%s".formatted(sourceOrdinal, itemIndex, normalizedCategory);
        return new Entry(jobId, category, "", "PENDING", jobId + ".png", null,
                "", BigDecimal.ZERO, "");
    }

    private Optional<Path> actualFile(Entry entry) {
        if (outputDirectory == null || !Files.isDirectory(outputDirectory)) return Optional.empty();
        String fileName = entry.outputFileName();
        if (fileName.isBlank() && !entry.jobId().isBlank()) fileName = entry.jobId() + ".png";
        if (fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) return Optional.empty();
        Path file = outputDirectory.resolve(fileName).normalize();
        return file.getParent().equals(outputDirectory) && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private static void readJsonLines(ObjectMapper objectMapper, Path file, Map<Key, Entry> entries) {
        if (file == null) return;
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("cutout JSONL file is invalid: " + file);
        try (var lines = Files.lines(file)) {
            lines.map(String::strip).filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String imageId = safe(node.path("imageId").asText());
                    int itemIndex = Math.max(1, node.path("itemIndex").asInt(1));
                    if (imageId.isBlank()) throw new IllegalArgumentException("cutout entry imageId is required");
                    Key key = new Key(imageId, itemIndex);
                    entries.merge(key, from(node), Entry::overlay);
                } catch (IOException failure) {
                    throw new IllegalArgumentException("invalid cutout JSONL entry in " + file, failure);
                }
            });
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read cutout JSONL file: " + file, failure);
        }
    }

    private static Entry from(JsonNode node) {
        String outputPath = safe(node.path("outputPath").asText());
        String fileName = outputPath.isBlank() ? "" : Path.of(outputPath).getFileName().toString();
        return new Entry(safe(node.path("jobId").asText()), safe(node.path("category").asText()),
                safe(node.path("displayName").asText()), normalizedStatus(node.path("status").asText()),
                fileName, null, safe(node.path("model").asText()),
                decimal(node.path("durationSeconds").asDouble(0d)), safe(node.path("error").asText()));
    }

    private static String normalizedStatus(String value) {
        return switch (safe(value).toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS", "SUCCEEDED", "COMPLETED", "READY" -> "READY";
            case "FAILED", "ERROR", "FAILED_REVIEW" -> "FAILED";
            case "SKIPPED" -> "SKIPPED";
            default -> "PENDING";
        };
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.max(0d, value)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\0', ' ').strip();
    }

    private record Key(String imageId, int itemIndex) { }

    record Entry(String jobId, String category, String displayName, String status, String outputFileName,
                 Path outputFile, String model, BigDecimal durationSeconds, String error) {
        static Entry pending() {
            return new Entry("", "", "", "PENDING", "", null, "", BigDecimal.ZERO, "");
        }

        Entry ready(Path file) {
            return new Entry(jobId, category, displayName, "READY", file.getFileName().toString(), file,
                    model, durationSeconds, "");
        }

        Entry withoutFile() {
            String effectiveStatus = "READY".equals(status) ? "PENDING" : status;
            return new Entry(jobId, category, displayName, effectiveStatus, outputFileName, null,
                    model, durationSeconds, error);
        }

        private static Entry overlay(Entry base, Entry update) {
            return new Entry(prefer(update.jobId, base.jobId), prefer(update.category, base.category),
                    prefer(update.displayName, base.displayName), preferStatus(update.status, base.status),
                    prefer(update.outputFileName, base.outputFileName), null, prefer(update.model, base.model),
                    update.durationSeconds.signum() > 0 ? update.durationSeconds : base.durationSeconds,
                    prefer(update.error, base.error));
        }

        private static String prefer(String primary, String fallback) {
            return primary == null || primary.isBlank() ? fallback : primary;
        }

        private static String preferStatus(String primary, String fallback) {
            return primary == null || primary.isBlank() || "PENDING".equals(primary) ? fallback : primary;
        }
    }
}
