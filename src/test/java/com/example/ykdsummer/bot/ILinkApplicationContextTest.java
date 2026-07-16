package com.example.ykdsummer.bot;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.service.ILinkBotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(properties = "ilink.enabled=false", webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ILinkApplicationContextTest {

    @Autowired
    private ILinkBotService botService;

    @Autowired
    private ILinkProperties properties;

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
}
