package com.example.ykdsummer.fashion.wardrobe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.service.ChatCompletionsVisionGateway;
import com.example.ykdsummer.ai.service.ImageInspectionService;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.fashion.wardrobe.domain.ClothingCompletenessStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Opt-in real-provider probe using a public product photo and a derivative with a partly hidden hem.
 * Normal builds never execute it or call a model.
 */
@SpringBootTest(
        properties = {
                "ilink.enabled=false",
                "app.persistence.enabled=false",
                "app.persistence.redis.enabled=false",
                "oss.image.enabled=false",
                "app.admin.enabled=false"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "FASHION_VISION_LIVE_TEST", matches = "true")
class FashionVisionLiveProbeTest {
    private static final String PUBLIC_TSHIRT_IMAGE = "https://perfecttshirtco.com/cdn/shop/products/"
            + "vintage-white-men-s-short-sleeve-crew-neck-t-shirt-perfect-tshirt-co-4.jpg?v=1747336738&width=1200";

    @Autowired
    private ChatCompletionsVisionGateway visionGateway;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Timeout(180)
    void acceptsACompleteGarmentAndAPartlyCoveredHem() throws Exception {
        byte[] publicPhoto = download(PUBLIC_TSHIRT_IMAGE);
        Path root = Files.createTempDirectory("fashion-vision-live-");
        try {
            LocalImageAssetStore store = new LocalImageAssetStore(root);
            FashionVisionCandidateAnalyzer analyzer = new FashionVisionCandidateAnalyzer(
                    new ImageInspectionService(visionGateway, store), objectMapper);

            long clearStarted = System.nanoTime();
            var clearResult = analyzer.analyze(store.saveIncoming("fashion-live-probe", "public clear garment", publicPhoto, "image/jpeg"));
            long clearDurationMs = Duration.ofNanos(System.nanoTime() - clearStarted).toMillis();

            long croppedStarted = System.nanoTime();
            var croppedResult = analyzer.analyze(store.saveIncoming("fashion-live-probe", "public cropped garment", cropLowerHem(publicPhoto), "image/jpeg"));
            long croppedDurationMs = Duration.ofNanos(System.nanoTime() - croppedStarted).toMillis();

            System.out.printf("FASHION_VISION_PROBE clearMs=%d clear=%s%n", clearDurationMs, summarize(clearResult));
            System.out.printf("FASHION_VISION_PROBE croppedMs=%d cropped=%s%n", croppedDurationMs, summarize(croppedResult));
            assertThat(clearResult.candidates()).isNotEmpty();
            assertThat(clearResult.candidates()).anyMatch(candidate -> candidate.completenessStatus() == ClothingCompletenessStatus.READY);
            assertThat(croppedResult.candidates()).isNotEmpty();
            assertThat(croppedResult.candidates()).anyMatch(candidate -> candidate.completenessStatus() == ClothingCompletenessStatus.READY);
        } finally {
            deleteTree(root);
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "YkdSummerFashionProbe/1.0").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
            throw new IllegalStateException("Could not download the public fashion probe image, status=" + response.statusCode());
        }
        return response.body();
    }

    private static byte[] cropLowerHem(byte[] source) throws Exception {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
        if (original == null || original.getWidth() < 20 || original.getHeight() < 20) {
            throw new IllegalArgumentException("Public probe image is not a readable raster image");
        }
        int croppedHeight = Math.max(10, Math.round(original.getHeight() * 0.62f));
        BufferedImage cropped = new BufferedImage(original.getWidth(), croppedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = cropped.createGraphics();
        try {
            graphics.drawImage(original, 0, 0, original.getWidth(), croppedHeight,
                    0, 0, original.getWidth(), croppedHeight, null);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(cropped, "jpg", output)) throw new IllegalStateException("JPEG encoder unavailable");
            return output.toByteArray();
        }
    }

    private static String summarize(WardrobePhotoAnalyzer.AnalysisResult result) {
        return result.candidates().stream()
                .map(candidate -> candidate.categoryCode() + ':' + candidate.completenessStatus()
                        + ':' + candidate.qualityScore())
                .collect(java.util.stream.Collectors.joining(", ", result.summary() + " | ", ""));
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    throw new IllegalStateException("Could not remove probe file", exception);
                }
            });
        }
    }
}
