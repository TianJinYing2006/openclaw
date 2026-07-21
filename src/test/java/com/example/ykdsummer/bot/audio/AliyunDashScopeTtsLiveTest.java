package com.example.ykdsummer.bot.audio;

import com.example.ykdsummer.bot.config.AliyunTtsProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实百炼 MP3 连通测试；普通测试没有环境变量时自动跳过。 */
class AliyunDashScopeTtsLiveTest {

    @Test
    void configuredWorkspaceCanGenerateMp3() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String baseUrl = System.getenv("ALIYUN_TTS_BASE_URL");
        assumeTrue(apiKey != null && !apiKey.isBlank());
        assumeTrue(baseUrl != null && !baseUrl.isBlank());

        AliyunTtsProperties properties = new AliyunTtsProperties();
        properties.setEnabled(true);
        properties.setApiKey(apiKey);
        properties.setBaseUrl(baseUrl);
        properties.setModel(System.getenv().getOrDefault("ALIYUN_TTS_MODEL", "cosyvoice-v3-flash"));
        properties.setVoice(System.getenv().getOrDefault("ALIYUN_TTS_VOICE", "longanyang"));

        byte[] bytes = new AliyunDashScopeTtsService(properties)
                .synthesize("你好，这是微信机器人阿里云语音合成测试。")
                .orElseThrow()
                .bytes();

        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(new String(bytes, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("ID3");
    }
}
