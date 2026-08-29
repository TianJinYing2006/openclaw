package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.TencentAsrProperties;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 腾讯云真实连通测试。普通 mvn test 没有密钥时自动跳过，避免误扣额度。
 */
class TencentCloudAsrLiveTest {

    @Test
    void configuredCredentialsCanReachSentenceRecognition() {
        String secretId = System.getenv("TENCENTCLOUD_SECRET_ID");
        String secretKey = System.getenv("TENCENTCLOUD_SECRET_KEY");
        assumeTrue(secretId != null && !secretId.isBlank());
        assumeTrue(secretKey != null && !secretKey.isBlank());

        TencentAsrProperties properties = new TencentAsrProperties();
        properties.setEnabled(true);
        properties.setSecretId(secretId);
        properties.setSecretKey(secretKey);

        TencentCloudAsrService service = new TencentCloudAsrService(properties);
        assertThatCode(() -> service.transcribe(oneSecondToneWav()))
                .doesNotThrowAnyException();
    }

    /** 生成 16kHz、单声道、16-bit PCM WAV，只用于认证和接口连通性检查。 */
    private static byte[] oneSecondToneWav() {
        int sampleRate = 16_000;
        int samples = sampleRate;
        int dataLength = samples * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLength);
        wav.put(new byte[]{'W', 'A', 'V', 'E'});
        wav.put(new byte[]{'f', 'm', 't', ' '}).putInt(16);
        wav.putShort((short) 1).putShort((short) 1);
        wav.putInt(sampleRate).putInt(sampleRate * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLength);
        for (int index = 0; index < samples; index++) {
            double phase = 2.0 * Math.PI * 440.0 * index / sampleRate;
            wav.putShort((short) (Math.sin(phase) * 4_000));
        }
        return wav.array();
    }
}
