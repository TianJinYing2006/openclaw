package com.example.ykdsummer.fashion.application;

import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.ai.service.LocalImageAssetStore.StoredImage;
import com.example.ykdsummer.fashion.domain.FashionReferenceGarment;
import com.example.ykdsummer.fashion.domain.FashionReferenceLook;
import com.example.ykdsummer.fashion.persistence.FashionReferenceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Idempotent bridge from the versioned annotation JSON into OSS/MySQL and the semantic outbox. */
@Service
@ConditionalOnProperty(prefix = "app.persistence", name = "enabled", havingValue = "true")
public class FashionReferenceImportService {
    public static final String PUBLIC_ASSET_OWNER = "fashion:public-reference-library";
    private static final String SUPPORTED_SCHEMA = "1.0.0";
    private final FashionReferenceRepository repository;
    private final LocalImageAssetStore images;
    private final ObjectMapper objectMapper;

    public FashionReferenceImportService(
            FashionReferenceRepository repository, LocalImageAssetStore images, ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.images = images;
        this.objectMapper = objectMapper;
    }

    public ImportReport importFile(Path annotationFile, Path imageDirectory, int limit, boolean publish) {
        Path json = requireFile(annotationFile, "annotationFile");
        Path directory = imageDirectory == null ? null : imageDirectory.toAbsolutePath().normalize();
        if (directory == null || !Files.isDirectory(directory)) throw new IllegalArgumentException("imageDirectory is invalid");
        JsonNode root = read(json);
        JsonNode annotations = root.path("annotations");
        List<JsonNode> source = new ArrayList<>();
        if (annotations.isArray()) annotations.forEach(source::add); else if (root.isObject()) source.add(root);
        int imported = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (JsonNode annotation : source) {
            if (imported >= Math.max(1, limit)) break;
            String imageId = text(annotation.path("imageId").asText(), 255);
            try {
                if (!SUPPORTED_SCHEMA.equals(annotation.path("schemaVersion").asText())) {
                    throw new IllegalArgumentException("unsupported schemaVersion");
                }
                if (!annotation.path("imageAssessment").path("usable").asBoolean(false)) {
                    skipped++;
                    continue;
                }
                JsonNode garmentNodes = annotation.path("garments");
                if (!garmentNodes.isArray() || garmentNodes.isEmpty()) {
                    skipped++;
                    continue;
                }
                Path imageFile = safeImagePath(directory, imageId);
                byte[] bytes = Files.readAllBytes(imageFile);
                String sha256 = sha256(bytes);
                Optional<FashionReferenceLook> existing = repository.findByReferenceCode(imageId);
                Optional<FashionReferenceLook> duplicate = repository.findBySha256(sha256);
                if (duplicate.isPresent() && existing.stream().noneMatch(value -> value.id() == duplicate.get().id())) {
                    skipped++;
                    continue;
                }
                StoredImage stored = existing.filter(value -> sha256.equals(value.sha256()))
                        .flatMap(value -> images.find(PUBLIC_ASSET_OWNER, value.imageAssetId(), value.imageAssetVersion()))
                        .orElseGet(() -> images.saveIncoming(PUBLIC_ASSET_OWNER,
                                "公共穿搭参考：" + imageId, bytes, mediaType(imageFile)));
                List<FashionReferenceGarment> garments = garments(garmentNodes);
                FashionReferenceLook look = new FashionReferenceLook(0L, imageId, lookName(garments),
                        stored.assetId(), stored.version(), mediaType(imageFile), imageId, "", "LOCAL_IMPORT",
                        publish ? "LOCAL_DEVELOPMENT_ONLY" : "UNVERIFIED", sha256, "", SUPPORTED_SCHEMA,
                        objectMapper.writeValueAsString(annotation), publish ? "ACTIVE" : "DRAFT", garments,
                        Instant.now(), Instant.now());
                repository.upsert(look);
                imported++;
            } catch (RuntimeException | IOException failure) {
                skipped++;
                failures.add((imageId.isBlank() ? "<unknown>" : imageId) + ": " + rootMessage(failure));
            }
        }
        return new ImportReport(imported, skipped, List.copyOf(failures));
    }

    private List<FashionReferenceGarment> garments(JsonNode nodes) {
        List<FashionReferenceGarment> values = new ArrayList<>();
        nodes.forEach(node -> {
            String displayName = text(node.path("displayName").asText(), 128);
            String category = code(node.path("categoryCode").asText(), "UNKNOWN");
            String subCategory = code(node.path("subCategoryCode").asText(), "UNKNOWN");
            if (displayName.isBlank() || "UNKNOWN".equals(subCategory)) return;
            JsonNode colors = node.path("colors");
            JsonNode visibility = node.path("visibility");
            JsonNode confidence = node.path("confidence");
            values.add(new FashionReferenceGarment(0L, 0L, Math.max(1, node.path("itemIndex").asInt(1)),
                    displayName, category, subCategory, code(node.path("targetGender").asText(), "UNISEX"),
                    code(colors.path("primaryCode").asText(), ""), strings(colors.path("secondaryCodes")),
                    strings(colors.path("accentCodes")), strings(node.path("styleCodes")),
                    code(node.path("fitCode").asText(), ""), code(node.path("patternCode").asText(), ""),
                    code(node.path("silhouetteCode").asText(), ""), code(node.path("lengthCode").asText(), ""),
                    strings(node.path("materialCodes")), strings(node.path("seasonCodes")),
                    strings(node.path("occasionCodes")), node.path("formalityLevel").asInt(0),
                    code(visibility.path("visibilityStatus").asText(), "UNKNOWN"),
                    decimal(visibility.path("visibleRatio").asDouble(0d)),
                    decimal(confidence.path("overall").asDouble(0d)), node.toString()));
        });
        if (values.isEmpty()) throw new IllegalArgumentException("no valid garments");
        return List.copyOf(values);
    }

    private static Path safeImagePath(Path directory, String imageId) {
        if (imageId.isBlank() || !Path.of(imageId).getFileName().toString().equals(imageId)) {
            throw new IllegalArgumentException("invalid imageId");
        }
        Path file = directory.resolve(imageId).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("image file is missing");
        }
        return file;
    }

    private JsonNode read(Path file) {
        try { return objectMapper.readTree(Files.readString(file)); }
        catch (IOException failure) { throw new IllegalArgumentException("cannot read annotation JSON", failure); }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) result.add(code(value.asText(), "")); });
        return result.stream().filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String lookName(List<FashionReferenceGarment> garments) {
        return garments.stream().map(FashionReferenceGarment::displayName).limit(3)
                .reduce((left, right) -> left + " + " + right).orElse("穿搭参考");
    }
    private static Path requireFile(Path value, String field) {
        if (value == null || !Files.isRegularFile(value.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.toAbsolutePath().normalize();
    }
    private static String mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(Math.max(0d, Math.min(1d, value)));
    }
    private static String code(String value, String fallback) {
        String cleaned = text(value, 64).toUpperCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
        return cleaned.matches("[A-Z0-9_]*") && !cleaned.isBlank() ? cleaned : fallback;
    }
    private static String text(String value, int limit) {
        String cleaned = value == null ? "" : value.replace('\0', ' ').strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }
    private static String rootMessage(Throwable failure) {
        Throwable current = failure; while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    public record ImportReport(int imported, int skipped, List<String> failures) { }
}
