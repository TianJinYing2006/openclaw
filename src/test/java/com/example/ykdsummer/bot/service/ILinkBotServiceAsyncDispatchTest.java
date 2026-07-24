package com.example.ykdsummer.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.runtime.ILinkDeliveryAudit;
import com.example.ykdsummer.bot.runtime.ILinkRuntimeState;
import com.example.ykdsummer.bot.session.ILinkSessionStore;
import com.example.ykdsummer.bot.video.ILinkVideoDownloader;
import io.github.morningwn.client.ILinkBot;
import io.github.morningwn.protocol.ProtocolValues;
import io.github.morningwn.protocol.WeixinMessage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ILinkBotServiceAsyncDispatchTest {

    @Test
    void mediaWorkDoesNotBlockLaterTextForTheSameUser() throws Exception {
        ILinkReplyService replyService = mock(ILinkReplyService.class);
        ILinkMessageRateLimiter rateLimiter = mock(ILinkMessageRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(replyService.isVideoMessage(anyList())).thenReturn(false);
        when(replyService.isMediaMessage(anyList())).thenReturn(true, false);

        CountDownLatch mediaStarted = new CountDownLatch(1);
        CountDownLatch releaseMedia = new CountDownLatch(1);
        CountDownLatch mediaFinished = new CountDownLatch(1);
        CountDownLatch textCompleted = new CountDownLatch(1);
        CountDownLatch repliesSent = new CountDownLatch(2);
        when(replyService.createReply(any(), anyList(), any())).thenAnswer(invocation -> {
            WeixinMessage message = invocation.getArgument(0);
            if (message.messageId() == 1L) {
                mediaStarted.countDown();
                assertThat(releaseMedia.await(2, TimeUnit.SECONDS)).isTrue();
                mediaFinished.countDown();
                return new ILinkReply.Text("媒体处理完成");
            }
            textCompleted.countDown();
            return new ILinkReply.Text("普通文字回复");
        });

        ILinkBotService service = new ILinkBotService(
                new ILinkProperties(), mock(ILinkSessionStore.class), new ILinkRuntimeState(),
                mock(ILinkDeliveryAudit.class), replyService, rateLimiter, mock(ILinkMediaDownloader.class),
                mock(ILinkFileDownloader.class), mock(ILinkVideoDownloader.class), new VideoProcessingProperties());
        ILinkBot bot = mock(ILinkBot.class);
        when(bot.isAutoPulling()).thenReturn(true);
        doAnswer(invocation -> {
            repliesSent.countDown();
            return null;
        }).when(bot).replyText(any(WeixinMessage.class), anyString());
        ReflectionTestUtils.setField(service, "bot", bot);

        try {
            service.handleInboundMessage(message(1L));
            assertThat(mediaStarted.await(1, TimeUnit.SECONDS)).isTrue();

            service.handleInboundMessage(message(2L));

            assertThat(textCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseMedia.countDown();
            assertThat(mediaFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(repliesSent.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseMedia.countDown();
            service.stop();
        }
    }

    private static WeixinMessage message(long id) {
        return new WeixinMessage(
                id, id, "same-user", "bot", "client", System.currentTimeMillis(), null, null,
                null, null, ProtocolValues.MESSAGE_TYPE_USER, ProtocolValues.MESSAGE_STATE_FINISH,
                List.of(), "context"
        );
    }
}
