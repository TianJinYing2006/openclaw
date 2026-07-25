package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.example.ykdsummer.ai.config.OssImageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real end-to-end verification for private OSS source images and OpenAI-compatible image edits.
 * The random OSS prefix is always cleaned up after the test.
 */
@EnabledIfEnvironmentVariable(named = "OSS_IMAGE_EDIT_LIVE_TEST", matches = "true")
class OpenAiImageEditOssLiveTest {

    @Test
    @Timeout(180)
    void readsPrivateOssImageEditsItAndWritesTheRevisionBackToOss() throws Exception {
        String runPrefix = "ilink-bot/live-image-edit/" + UUID.randomUUID();
        String userId = "oss-image-edit-test-" + UUID.randomUUID();
        OssImageProperties ossProperties = ossProperties(runPrefix);
        OSS client = new OSSClientBuilder().build(ossProperties.getEndpoint(),
                ossProperties.getAccessKeyId(), ossProperties.getAccessKeySecret());
        OssImageAssetStore store = new OssImageAssetStore(ossProperties, client);
        try {
            var original = store.saveGenerated(userId, "blue square source", sourcePng(), null);
            String signedUrl = store.signedReadUrl(original);

            var edited = imageEditGateway().edit(
                    "Keep the simple square composition and change the blue square to a red square.", signedUrl);

            assertThat(edited.hasImage()).as(edited.errorMessage()).isTrue();
            var revision = store.saveRevision(userId, original.assetId(), "change square to red",
                    edited.imageBytes(), edited.remoteUrl());
            assertThat(revision.version()).isEqualTo(2);
            assertThat(store.readBytes(revision)).isNotEmpty();
        } finally {
            deleteAllUnderPrefix(client, ossProperties.getBucketName(), runPrefix);
            client.shutdown();
        }
    }

    private static OpenAiImageEditGateway imageEditGateway() {
        ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
        image.setBaseUrl(required("IMAGE_API_BASE_URL"));
        image.setApiKey(required("IMAGE_API_KEY"));
        AiProperties ai = new AiProperties();
        ai.setImageModel(required("IMAGE_MODEL"));
        ai.setImageSize("1024x1024");
        ai.setImageQuality("high");
        ai.setImageTimeout(Duration.ofSeconds(150));
        return new OpenAiImageEditGateway(image, ai, new ObjectMapper());
    }

    private static OssImageProperties ossProperties(String prefix) {
        OssImageProperties properties = new OssImageProperties();
        properties.setEndpoint(required("ALIOSS_ENDPOINT"));
        properties.setAccessKeyId(required("ALIOSS_ACCESS_KEY_ID"));
        properties.setAccessKeySecret(required("ALIOSS_ACCESS_KEY_SECRET"));
        properties.setBucketName(required("ALIOSS_BUCKET_NAME"));
        properties.setPrefix(prefix);
        properties.setSignedUrlTtl(Duration.ofMinutes(2));
        return properties;
    }

    private static byte[] sourcePng() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(20, 90, 210));
            graphics.fillRect(112, 112, 288, 288);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing live test environment variable: " + name);
        }
        return value;
    }

    private static void deleteAllUnderPrefix(OSS client, String bucket, String prefix) {
        String marker = null;
        do {
            ObjectListing listing = client.listObjects(new ListObjectsRequest(bucket)
                    .withPrefix(prefix).withMarker(marker).withMaxKeys(1000));
            listing.getObjectSummaries().forEach(object -> client.deleteObject(bucket, object.getKey()));
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null);
    }
}
