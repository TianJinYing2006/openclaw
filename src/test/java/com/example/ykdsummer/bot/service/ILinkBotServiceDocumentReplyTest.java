package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.bot.config.ILinkProperties;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import com.example.ykdsummer.bot.runtime.ILinkDeliveryAudit;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
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
                mock(ILinkDeliveryAudit.class),
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

    @Test
    void sendsGeneratedMp3WithoutAnExtraTextReply() {
        ILinkReplyService replyService = mock(ILinkReplyService.class);
        ILinkRuntimeState runtimeState = mock(ILinkRuntimeState.class);
        ILinkBotService service = new ILinkBotService(
                new ILinkProperties(), mock(ILinkSessionStore.class), runtimeState, mock(ILinkDeliveryAudit.class),
                replyService,
                mock(ILinkMessageRateLimiter.class), mock(ILinkMediaDownloader.class),
                mock(ILinkFileDownloader.class), mock(ILinkVideoDownloader.class), new VideoProcessingProperties());
        ILinkBot bot = mock(ILinkBot.class);
        when(bot.isAutoPulling()).thenReturn(true);
        ReflectionTestUtils.setField(service, "bot", bot);
        WeixinMessage message = message();
        byte[] bytes = {7, 8, 9};
        when(replyService.createReply(message, List.of(), service.status()))
                .thenReturn(new ILinkReply.AudioFile("answer.mp3", bytes));

        try {
            ReflectionTestUtils.invokeMethod(service, "processReply", message, List.of());
            var order = inOrder(bot);
            order.verify(bot).sendFile("user", "context", "answer.mp3", bytes);
        } finally {
            service.stop();
        }
    }

    @Test
    void keepsTheAttachmentFailureAndTriesToNotifyTheWechatUser() {
        ILinkReplyService replyService = mock(ILinkReplyService.class);
        ILinkRuntimeState runtimeState = mock(ILinkRuntimeState.class);
        ILinkDeliveryAudit audit = mock(ILinkDeliveryAudit.class);
        ILinkBotService service = new ILinkBotService(
                new ILinkProperties(), mock(ILinkSessionStore.class), runtimeState, audit, replyService,
                mock(ILinkMessageRateLimiter.class), mock(ILinkMediaDownloader.class),
                mock(ILinkFileDownloader.class), mock(ILinkVideoDownloader.class), new VideoProcessingProperties());
        ILinkBot bot = mock(ILinkBot.class);
        when(bot.isAutoPulling()).thenReturn(true);
        ReflectionTestUtils.setField(service, "bot", bot);
        WeixinMessage message = message();
        byte[] bytes = {1, 2, 3};
        when(replyService.createReply(message, List.of(), service.status()))
                .thenReturn(new ILinkReply.DocumentFile("report.pdf", bytes, ""));
        doThrow(new IllegalStateException("CDN upload failed"))
                .when(bot).sendFile("user", "context", "report.pdf", bytes);

        try {
            ReflectionTestUtils.invokeMethod(service, "processReply", message, List.of());
            var order = inOrder(bot);
            order.verify(bot).sendFile("user", "context", "report.pdf", bytes);
            order.verify(bot).replyText(message, "文件已在机器人本地生成，但上传或发送到微信失败。请稍后重新执行原请求。");
            verify(runtimeState).messageDeliveryFailed("CDN upload failed");
            verify(runtimeState).fallbackMessageSent();
            verify(audit).failed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq("document"), org.mockito.ArgumentMatchers.eq(3),
                    org.mockito.ArgumentMatchers.any(IllegalStateException.class));
        } finally {
            service.stop();
        }
    }

    @Test
    void notifiesWechatWhenGeneratedImageCannotBeUploaded() {
        ILinkReplyService replyService = mock(ILinkReplyService.class);
        ILinkRuntimeState runtimeState = mock(ILinkRuntimeState.class);
        ILinkBotService service = new ILinkBotService(
                new ILinkProperties(), mock(ILinkSessionStore.class), runtimeState, mock(ILinkDeliveryAudit.class), replyService,
                mock(ILinkMessageRateLimiter.class), mock(ILinkMediaDownloader.class),
                mock(ILinkFileDownloader.class), mock(ILinkVideoDownloader.class), new VideoProcessingProperties());
        ILinkBot bot = mock(ILinkBot.class);
        when(bot.isAutoPulling()).thenReturn(true);
        ReflectionTestUtils.setField(service, "bot", bot);
        WeixinMessage message = message();
        byte[] bytes = {4, 5, 6};
        when(replyService.createReply(message, List.of(), service.status()))
                .thenReturn(new ILinkReply.Image(bytes, ""));
        doThrow(new IllegalStateException("CDN upload failed"))
                .when(bot).sendImage("user", "context", bytes);

        try {
            ReflectionTestUtils.invokeMethod(service, "processReply", message, List.of());
            verify(bot).replyText(message, "图片已在机器人本地生成，但上传或发送到微信失败。请稍后重新执行原请求。");
            verify(runtimeState).fallbackMessageSent();
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
