package com.wechatbot.fashion.bot;

import com.wechatbot.fashion.ai.config.ImageOpenAiClientProperties;
import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.ai.orchestration.BoundedToolCallingManager;
import com.wechatbot.fashion.ai.service.LlmGateway;
import com.wechatbot.fashion.ai.service.RoutingLlmGateway;
import com.wechatbot.fashion.bot.config.ILinkProperties;
import com.wechatbot.fashion.bot.config.ILinkRateLimitProperties;
import com.wechatbot.fashion.bot.config.FileProcessingProperties;
import com.wechatbot.fashion.bot.config.VideoProcessingProperties;
import com.wechatbot.fashion.bot.service.ILinkBotService;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = {
        "ilink.enabled=false",
        "app.persistence.enabled=true",
        "app.persistence.redis.enabled=false",
        "app.fashion.semantic.enabled=false",
        "app.fashion.reference.enabled=false"
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ILinkApplicationContextTest {

    @Autowired
    private ILinkBotService botService;

    @Autowired
    private ILinkProperties properties;

    @Autowired
    private ImageOpenAiClientProperties imageProperties;

    @Autowired
    private VideoProcessingProperties videoProperties;

    @Autowired
    private FileProcessingProperties fileProperties;

    @Autowired
    private ILinkRateLimitProperties rateLimitProperties;

    @Autowired
    private ChatModel springAiChatModel;

    @Autowired
    private ToolCallingManager toolCallingManager;

    @Autowired
    private LlmGateway llmGateway;

    @Autowired
    private AiProperties aiProperties;

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
    void bindsIndependentOpenAiImagesConfigurationWithoutSharingTheTextProtocolClient() {
        assertEquals("", imageProperties.getBaseUrl());
        assertEquals("gpt-image-2", aiProperties.getImageModel());
    }

    @Test
    void bindsSafeVideoDefaults() {
        assertEquals(20, videoProperties.getMaxVideoSize().toMegabytes());
        assertEquals(60, videoProperties.getMaxDuration().toSeconds());
        assertEquals(10, videoProperties.getMaxFrames());
        assertEquals(5, videoProperties.getQueueCapacity());
    }

    @Test
    void bindsSafeFileOutputDefault() {
        assertEquals(20 * 1024 * 1024, fileProperties.getMaxOutputBytes());
    }

    @Test
    void loadsSpringAiAndBoundedQueueDefaults() {
        assertEquals("OpenAiChatModel", springAiChatModel.getClass().getSimpleName());
        assertEquals(RoutingLlmGateway.class, llmGateway.getClass());
        assertEquals(600, aiProperties.getMaxCompletionTokens());
        assertEquals(4, aiProperties.getMaxAgentRounds());
        // 运行环境可以通过 AI_SYSTEM_PROMPT 覆盖默认提示词，因此不能假设值与源码常量完全相同。
        assertFalse(aiProperties.getSystemPrompt().isBlank());
        assertEquals(100, properties.getTextQueueCapacity());
        assertEquals(10, properties.getImageQueueCapacity());
        assertEquals(20, rateLimitProperties.limitFor("text"));
        assertEquals(2, rateLimitProperties.limitFor("video"));
    }

    @Test
    void usesTheFourRoundAgentToolCallingGuard() {
        assertEquals(BoundedToolCallingManager.class, toolCallingManager.getClass());
        assertEquals(4, aiProperties.getMaxAgentRounds());
    }
}
