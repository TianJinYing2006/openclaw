package com.example.demo;

import com.example.demo.chat.CommandHandler;
import com.example.demo.wechat.ILinkService;
import com.example.demo.chat.SessionManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主测试类 — 上下文加载 + 图片收发 + 多模态 LLM + 会话管理
 */
@SpringBootTest
@ActiveProfiles("test")
class Demo1ApplicationTests {

    private static final Logger log = LoggerFactory.getLogger(Demo1ApplicationTests.class);

    @Autowired
    private CommandHandler commandHandler;

    @Autowired
    private ILinkService iLinkService;

    @Autowired
    private SessionManager sessionManager;

    // ===== 基础 =====

    @Test
    void contextLoads() {
        log.info("✅ Spring 上下文加载成功");
    }

    // ===== 1. 图片接收 + 多模态 LLM 流程 =====

    @Test
    void testChatWithImage() {
        byte[] imageBytes = createTestImage();
        String mimeType = "image/png";
        log.info("测试图片已生成: size={} bytes", imageBytes.length);

        String firstReply = commandHandler.handle("你好", "test-user");
        log.info("第一轮回复: {}", firstReply);
        assertNotNull(firstReply);
        assertFalse(firstReply.contains("未配置"));

        String secondReply = commandHandler.handle("接下来我会发一张图片给你", "test-user");
        log.info("第二轮回复: {}", secondReply);
        assertNotNull(secondReply);

        String imageReply = commandHandler.handle(
                "这张图里有什么？", "test-user", imageBytes, mimeType);
        log.info("图片回复: {}", imageReply);
        assertNotNull(imageReply);
        System.out.println("===== 图片识别结果 =====");
        System.out.println(imageReply);
        System.out.println("========================");

        String followUp = commandHandler.handle(
                "刚才那张图是什么颜色的？", "test-user");
        log.info("追问回复: {}", followUp);
        assertNotNull(followUp);
        System.out.println("===== 追问结果（多轮记忆） =====");
        System.out.println(followUp);
        System.out.println("================================");

        String helpReply = commandHandler.handle("/help", "test-user");
        log.info("/help 回复: {}", helpReply);
        assertNotNull(helpReply);
        assertTrue(helpReply.contains("/help"), "/help 命令应正常返回");

        sessionManager.clearSession("test-user");
    }

    // ===== 2. 图片发送流程 =====

    @Test
    void testSendImageWithTypingService() {
        byte[] imageBytes = createTestImage();
        assertDoesNotThrow(() ->
            iLinkService.sendImageWithTyping("test-user", imageBytes, "test.png", "测试图片", 1500L)
        );
        log.info("✅ sendImageWithTyping 调用成功（无异常）");
    }

    @Test
    void testSendImageService() {
        byte[] imageBytes = createTestImage();
        assertDoesNotThrow(() ->
            iLinkService.sendImage("test-user", imageBytes, "photo.jpg", "")
        );
        log.info("✅ sendImage 调用成功（无异常）");
    }

    @Test
    void testSendImageNullBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendImage("test-user", null, "empty.png", "")
        );
        log.info("✅ null 图片字节不抛异常");
    }

    @Test
    void testSendImageEmptyBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendImage("test-user", new byte[0], "empty.png", "")
        );
        log.info("✅ 空字节数组不抛异常");
    }

    @Test
    void testSendImageWithTypingNullBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendImageWithTyping("test-user", null, "empty.png", "", 1500L)
        );
        log.info("✅ sendImageWithTyping null 字节不抛异常");
    }

    // ===== 语音发送 =====

    @Test
    void testSendVoiceService() {
        byte[] voiceBytes = createTestWav();
        assertDoesNotThrow(() ->
            iLinkService.sendVoice("test-user", voiceBytes, "test.wav", 2000, 16000)
        );
        log.info("✅ sendVoice 调用成功（无异常）");
    }

    @Test
    void testSendVoiceNullBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendVoice("test-user", null, "test.wav", 2000, 16000)
        );
        log.info("✅ sendVoice null 字节不抛异常");
    }

    @Test
    void testSendVoiceEmptyBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendVoice("test-user", new byte[0], "test.wav", 2000, 16000)
        );
        log.info("✅ sendVoice 空字节不抛异常");
    }

    @Test
    void testSendVoiceWithTypingService() {
        byte[] voiceBytes = createTestWav();
        assertDoesNotThrow(() ->
            iLinkService.sendVoiceWithTyping("test-user", voiceBytes, "test.wav",
                    "语音消息", 2000, 16000, 1500L)
        );
        log.info("✅ sendVoiceWithTyping 调用成功（无异常）");
    }

    @Test
    void testSendVoiceWithTypingNullBytes() {
        assertDoesNotThrow(() ->
            iLinkService.sendVoiceWithTyping("test-user", null, "test.wav",
                    "", 2000, 16000, 1500L)
        );
        log.info("✅ sendVoiceWithTyping null 字节不抛异常");
    }

    @Test
    void testSendVoiceReplyService() {
        assertDoesNotThrow(() ->
            iLinkService.sendVoiceReply("test-user", "这是一条测试语音回复")
        );
        log.info("✅ sendVoiceReply 调用成功（无异常）");
    }


    // ===== 3. 会话管理 =====

    @Test
    void testSessionManagement() {
        assertFalse(sessionManager.hasSession("session-test-user"));
        assertEquals(0, sessionManager.getActiveSessionCount());

        sessionManager.addMessage("session-test-user", "user", "你好");
        assertTrue(sessionManager.hasSession("session-test-user"));
        assertEquals(1, sessionManager.getActiveSessionCount());

        for (int i = 0; i < 25; i++) {
            sessionManager.addMessage("session-test-user", "user", "消息" + i);
            sessionManager.addMessage("session-test-user", "assistant", "回复" + i);
        }
        assertEquals(40, sessionManager.getHistory("session-test-user").size());

        sessionManager.clearSession("session-test-user");
        assertFalse(sessionManager.hasSession("session-test-user"));
        assertEquals(0, sessionManager.getActiveSessionCount());

        log.info("✅ 会话管理测试通过");
    }

    // ===== 工具方法 =====

    private byte[] createTestImage() {
        try {
            int width = 200;
            int height = 150;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();

            g.setColor(Color.BLUE);
            g.fillRect(0, 0, width, height);

            g.setColor(Color.RED);
            g.fillOval(30, 20, 140, 100);

            g.setColor(Color.GREEN);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.drawString("Test", 70, 90);

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成测试图片失败", e);
        }
    }

    /**
     * 在内存中生成一个 16kHz 1秒 的 WAV 音频文件（440Hz 正弦波）
     */
    private byte[] createTestWav() {
        try {
            int sampleRate = 16000;
            int durationSec = 1;
            int numSamples = sampleRate * durationSec;
            short[] samples = new short[numSamples];

            for (int i = 0; i < numSamples; i++) {
                double angle = 2.0 * Math.PI * 440.0 * i / sampleRate;
                samples[i] = (short) (Math.sin(angle) * Short.MAX_VALUE * 0.3);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int dataSize = numSamples * 2; // 16-bit samples
            int chunkSize = 36 + dataSize;

            // RIFF header
            baos.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt32(baos, chunkSize);
            baos.write(new byte[]{'W', 'A', 'V', 'E'});
            // fmt subchunk
            baos.write(new byte[]{'f', 'm', 't', ' '});
            writeInt32(baos, 16);       // subchunk1 size
            writeInt16(baos, 1);        // PCM
            writeInt16(baos, 1);        // mono
            writeInt32(baos, sampleRate);
            writeInt32(baos, sampleRate * 2); // byte rate
            writeInt16(baos, 2);        // block align
            writeInt16(baos, 16);       // bits per sample
            // data subchunk
            baos.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt32(baos, dataSize);
            for (short s : samples) {
                writeInt16(baos, s);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成测试 WAV 失败", e);
        }
    }

    private void writeInt32(ByteArrayOutputStream baos, int val) {
        baos.write(val & 0xff);
        baos.write((val >> 8) & 0xff);
        baos.write((val >> 16) & 0xff);
        baos.write((val >> 24) & 0xff);
    }

    private void writeInt16(ByteArrayOutputStream baos, int val) {
        baos.write(val & 0xff);
        baos.write((val >> 8) & 0xff);
    }
}
