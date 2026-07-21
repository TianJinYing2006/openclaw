package com.example.demo.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TtsIntegrationTest {

    @Autowired
    private SpeechSynthesisService ttsService;

    @Test
    void testTtsSynthesizeAndSaveWav() throws Exception {
        String text = "你好，这是一个语音测试。欢迎使用百炼语音合成服务。";
        System.out.println("===== 开始 TTS 合成 =====");
        System.out.println("文本: " + text);

        List<SpeechSynthesisService.SynthesizedAudio> audios = ttsService.synthesize(text);

        assertNotNull(audios);
        assertFalse(audios.isEmpty());
        System.out.println("合成段数: " + audios.size());

        for (int i = 0; i < audios.size(); i++) {
            SpeechSynthesisService.SynthesizedAudio audio = audios.get(i);
            System.out.println("  第 " + (i + 1) + " 段: "
                    + audio.audioBytes().length + " bytes, "
                    + audio.sampleRate() + "Hz, "
                    + audio.bitsPerSample() + "bit, "
                    + audio.durationMs() + "ms");

            assertTrue(audio.audioBytes().length > 44, "WAV 数据应该大于 44 字节的头部");
            assertTrue(audio.durationMs() > 0, "语音时长应大于 0");

            // 保存 WAV 文件
            String fileName = "tts-output-" + i + ".wav";
            Path outputPath = Paths.get(fileName);
            Files.write(outputPath, audio.audioBytes());
            System.out.println("  已保存: " + outputPath.toAbsolutePath());
        }

        System.out.println("===== TTS 合成完成 =====");
    }
    @Test
    void differentVoicesProduceDifferentWavFiles() throws Exception {
        String text = "你好，这是一段用于验证音色切换的测试文本。";
        byte[] cherryAudio = ttsService.synthesize(text, "Cherry").get(0).audioBytes();
        byte[] serenaAudio = ttsService.synthesize(text, "Serena").get(0).audioBytes();

        assertFalse(Arrays.equals(cherryAudio, serenaAudio),
                "Cherry and Serena must produce different audio bytes");
    }
}
