package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.session.ILinkSessionStore;
import com.example.ykdsummer.bot.video.ILinkVideoDownloader;
import io.github.morningwn.client.ILinkBot;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.WeixinMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ILinkBotServiceDocumentReplyTest {

    @Test
    void sendsDocumentBeforeItsFollowUpInstructions() {
        ILinkReplyService replyService = mock(ILinkReplyService.class);
        ILinkRuntimeState runtimeState = mock(ILinkRuntimeState.class);
        ILinkBotService service = new ILinkBotService(
                new ILinkProperties(),
                mock(ILinkSessionStore.class),
                runtimeState,
                replyService,
                mock(ILinkMessageRateLimiter.class),
                mock(ILinkMediaDownloader.class),
                mock(ILinkFileDownloader.class),
                mock(ILinkVideoDownloader.class),
                new VideoProcessingProperties()
        );
        ILinkBot bot = mock(ILinkBot.class);
        when(bot.isAutoPulling()).thenReturn(true);
        ReflectionTestUtils.setField(service, "bot", bot);
        WeixinMessage message = message();
        byte[] bytes = {1, 2, 3};
        when(replyService.createReply(message, List.of(), service.status()))
                .thenReturn(new ILinkReply.DocumentFile("report_v2.pdf", bytes, "继续修改或发送完成"));

        try {
            ReflectionTestUtils.invokeMethod(service, "processReply", message, List.of());
            var order = inOrder(bot);
            order.verify(bot).sendFile("user", "context", "report_v2.pdf", bytes);
            order.verify(bot).replyText(message, "继续修改或发送完成");
        } finally {
            service.stop();
        }
    }

    private static WeixinMessage message() {
        return new WeixinMessage(
                1L, 1L, "user", "bot", "client", System.currentTimeMillis(), null, null,
                null, null, ProtocolValues.MESSAGE_TYPE_USER, ProtocolValues.MESSAGE_STATE_FINISH,
                List.of(), "context"
        );
    }
}
