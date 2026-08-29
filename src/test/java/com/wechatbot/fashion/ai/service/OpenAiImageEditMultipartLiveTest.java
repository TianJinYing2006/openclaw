package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Explicit provider capability probe for two-reference virtual try-on previews.
 * It only uses public images and never writes an OSS object, database row, or WeChat message.
 */
@SpringBootTest(properties = {"ilink.enabled=false", "app.admin.enabled=false"}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "FASHION_VTON_MULTIPART_LIVE_TEST", matches = "true")
class OpenAiImageEditMultipartLiveTest {
    private static final String PERSON = "https://images.pexels.com/photos/6520931/"
            + "pexels-photo-6520931.jpeg?auto=compress&cs=tinysrgb&w=900";
    private static final String GARMENT = "https://qnam.smzdm.com/202106/10/60c18f1feccde7946.jpg_e1080.jpg";

    @Autowired
    private AsyncImageEditGateway imageEditGateway;

    @Test
    @Timeout(150)
    void providerAcceptsPersonAndGarmentReferencesInOneEditRequest() {
        long started = System.nanoTime();
        AsyncImageEditGateway.EditResult result = imageEditGateway.edit("""
                First reference image: the person. Second reference image: the clothing source.
                Create a realistic full-body virtual try-on preview. Keep the person's identity, face, pose,
                body proportions, background, and lighting. Put the referenced clothing naturally on that person.
                Do not add text, watermark, accessories, or extra people.
                """, List.of(PERSON, GARMENT));
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("FASHION_VTON_MULTIPART_PROBE durationMs=%d accepted=%s resultBytes=%d error=%s%n",
                durationMs, result.hasImage(), result.imageBytes() == null ? 0 : result.imageBytes().length,
                result.errorMessage() == null ? "" : result.errorMessage().replace('\n', ' '));
        assertThat(result.hasImage()).as(result.errorMessage()).isTrue();
    }
}
