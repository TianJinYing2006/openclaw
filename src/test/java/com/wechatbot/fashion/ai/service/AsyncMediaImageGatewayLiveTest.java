package com.wechatbot.fashion.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.config.ImageOpenAiClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * 真实异步改图冒烟测试。默认跳过；显式设置 IMAGE_EDIT_LIVE_TEST=true 后才会访问图片服务。
 * 它不读取用户图片、不写入图片资产，只验证创建任务、轮询和下载的完整网关协议。
 */
@EnabledIfEnvironmentVariable(named = "IMAGE_EDIT_LIVE_TEST", matches = "true")
class AsyncMediaImageGatewayLiveTest {

    @Test
    @Timeout(420)
    void createsPollsAndDownloadsARealRevision() throws Exception {
        ImageOpenAiClientProperties image = new ImageOpenAiClientProperties();
        image.setApiKey(requiredFirst("IMAGE_API_KEY"));
        String baseUrl = firstPresent("IMAGE_API_BASE_URL");
        if (!baseUrl.isBlank()) {
            image.setBaseUrl(baseUrl);
        }
        AiProperties ai = new AiProperties();
        ai.setImageModel(firstPresentOrDefault("gpt-image-2", "IMAGE_MODEL"));
        ai.setImageTimeout(java.time.Duration.ofMinutes(6));
        ai.setImagePollInterval(java.time.Duration.ofSeconds(3));

        AsyncImageEditGateway.EditResult result = new AsyncMediaImageGateway(
                image, ai, new ObjectMapper(), RestClient.builder().build())
                .edit("保持图片主体与构图不变，只把蓝色方块改为红色方块。", testImageDataUri());

        assertThat(result.hasImage()).as(result.errorMessage()).isTrue();
        assertThat(result.imageBytes()).isNotEmpty();
    }

    private static String testImageDataUri() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(245, 245, 245));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(30, 110, 220));
            graphics.fillRect(112, 112, 288, 288);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    private static String requiredFirst(String... names) {
        String value = firstPresent(names);
        if (value.isBlank()) {
            throw new IllegalStateException("缺少图片服务环境变量：" + String.join(" / ", names));
        }
        return value;
    }

    private static String firstPresentOrDefault(String fallback, String... names) {
        String value = firstPresent(names);
        return value.isBlank() ? fallback : value;
    }

    private static String firstPresent(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
