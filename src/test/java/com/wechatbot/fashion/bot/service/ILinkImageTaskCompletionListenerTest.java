package com.wechatbot.fashion.bot.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.wechatbot.fashion.ai.tool.ImageTaskCompletionEvent;
import com.wechatbot.fashion.bot.runtime.ILinkReplyContextStore;
import org.junit.jupiter.api.Test;

class ILinkImageTaskCompletionListenerTest {

    @Test
    void sendsCompletedImageWithTheUsersRecentReplyContext() {
        ILinkBotService botService = mock(ILinkBotService.class);
        ILinkReplyContextStore contexts = new ILinkReplyContextStore();
        contexts.remember("user-1", "context-1");
        ILinkImageTaskCompletionListener listener = new ILinkImageTaskCompletionListener(botService, contexts);

        listener.sendCompletedImage(new ImageTaskCompletionEvent(
                "user-1", "imgtask_1", new byte[]{1, 2, 3}, "img_1", 1));

        verify(botService).sendGeneratedImage(eq("user-1"), eq("context-1"), eq("imgtask_1"), any(byte[].class));
    }

    @Test
    void doesNotAttemptDeliveryWithoutARecentReplyContext() {
        ILinkBotService botService = mock(ILinkBotService.class);
        ILinkImageTaskCompletionListener listener = new ILinkImageTaskCompletionListener(
                botService, new ILinkReplyContextStore());

        listener.sendCompletedImage(new ImageTaskCompletionEvent(
                "user-1", "imgtask_1", new byte[]{1}, "img_1", 1));

        verifyNoInteractions(botService);
    }
}
