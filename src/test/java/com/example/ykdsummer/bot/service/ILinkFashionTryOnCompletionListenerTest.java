package com.example.ykdsummer.bot.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.fashion.runtime.FashionTryOnCompletedEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ILinkFashionTryOnCompletionListenerTest {

    @Test
    void sendsTheFinishedTryOnImageAndAPlainCompletionMessageToTheSameUser() {
        ILinkBotService bot = mock(ILinkBotService.class);
        ILinkReplyContextStore contexts = mock(ILinkReplyContextStore.class);
        when(contexts.find("wechat-user")).thenReturn(Optional.of("reply-context"));

        new ILinkFashionTryOnCompletionListener(bot, contexts).pushCompletedTryOn(
                new FashionTryOnCompletedEvent("wechat-user", "internal-task", 41L,
                        new byte[]{1, 2, 3}, "img_tryon", 1));

        verify(bot).sendImage(eq("wechat-user"), eq("reply-context"), eq(new byte[]{1, 2, 3}));
        verify(bot).sendText(eq("wechat-user"), eq("reply-context"), contains("上身效果图已生成"));
    }
}
