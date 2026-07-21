package com.example.ykdsummer.bot;

import com.example.ykdsummer.ai.config.ImageOpenAiClientProperties;
import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.ILinkRateLimitProperties;
import com.example.ykdsummer.bot.config.DocumentEditProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.service.ILinkBotService;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@SpringBootTest(properties = "ilink.enabled=false", webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ILinkApplicationContextTest {

    @Autowired
    private ILinkBotService botService;

    @Autowired
    private ILinkProperties properties;

    @Autowired
    private ImageOpenAiClientProperties imageClientProperties;

    @Autowired
    private VideoProcessingProperties videoProperties;

    @Autowired
    private DocumentEditProperties documentProperties;

    @Autowired
    private ILinkRateLimitProperties rateLimitProperties;

    @Autowired
    @Qualifier("openAIClient")
    private OpenAIClient textClient;

    @Autowired
    @Qualifier("imageOpenAIClient")
    private OpenAIClient imageClient;

    @Autowired
    private ChatModel springAiChatModel;

    @Test
    void startsWithoutContactingWeChatWhenDisabled() {
        var status = botService.status();
        assertFalse(status.enabled());
        assertFalse(status.polling());
        assertEquals("DISABLED", status.connectionStatus());
    }

    @Test
    void readsChineseFixedReplyAsUtf8() {
        assertEquals("你好，我已经收到你的文本消息。", properties.getFixedReply());
    }

    @Test
    void keepsTextAndImageAiClientsSeparate() {
        assertEquals("https://api.lk888.ai/v1", imageClientProperties.getBaseUrl());
        assertNotSame(textClient, imageClient);
    }

    @Test
    void bindsSafeVideoDefaults() {
        assertEquals(20, videoProperties.getMaxVideoSize().toMegabytes());
        assertEquals(60, videoProperties.getMaxDuration().toSeconds());
        assertEquals(10, videoProperties.getMaxFrames());
        assertEquals(5, videoProperties.getQueueCapacity());
    }

    @Test
    void bindsSafeDocumentModeDefaults() {
        assertEquals(20, documentProperties.getMaxVersions());
        assertEquals(2, documentProperties.getIdleTimeout().toHours());
        assertEquals(20 * 1024 * 1024, documentProperties.getMaxOutputBytes());
    }

    @Test
    void loadsSpringAiAndBoundedQueueDefaults() {
        assertEquals("OpenAiChatModel", springAiChatModel.getClass().getSimpleName());
        assertEquals(100, properties.getTextQueueCapacity());
        assertEquals(10, properties.getImageQueueCapacity());
        assertEquals(20, rateLimitProperties.limitFor("text"));
        assertEquals(2, rateLimitProperties.limitFor("video"));
    }
}
