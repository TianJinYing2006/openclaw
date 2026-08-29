package com.wechatbot.fashion.bot.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.wechatbot.fashion.bot.runtime.ILinkReplyContextStore;
import com.wechatbot.fashion.wardrobe.runtime.FashionGarmentCutoutCompletedEvent;
import com.wechatbot.fashion.wardrobe.runtime.FashionGarmentCutoutFailedEvent;
import org.junit.jupiter.api.Test;

class ILinkFashionGarmentCutoutCompletionListenerTest {

    @Test
    void pushesCompletedCutoutAndTheFinalConfirmationInstruction() {
        ILinkBotService bot = mock(ILinkBotService.class);
        ILinkReplyContextStore contexts = new ILinkReplyContextStore();
        contexts.remember("wechat-user", "context-1");
        ILinkFashionGarmentCutoutCompletionListener listener = new ILinkFashionGarmentCutoutCompletionListener(bot, contexts);

        listener.pushCompletedCutout(new FashionGarmentCutoutCompletedEvent(
                "wechat-user", "task-1", "candidate-1", new byte[] {1, 2, 3}, "img_cutout", 1));

        verify(bot).sendImage(eq("wechat-user"), eq("context-1"),
                argThat(bytes -> java.util.Arrays.equals(bytes, new byte[] {1, 2, 3})));
        verify(bot).sendText(eq("wechat-user"), eq("context-1"),
                argThat(text -> text.contains("确认加入衣橱") && !text.contains("candidate-1")));
    }

    @Test
    void doesNotSendWithoutARecentWechatReplyContext() {
        ILinkBotService bot = mock(ILinkBotService.class);
        ILinkFashionGarmentCutoutCompletionListener listener = new ILinkFashionGarmentCutoutCompletionListener(
                bot, new ILinkReplyContextStore());

        listener.pushCompletedCutout(new FashionGarmentCutoutCompletedEvent(
                "wechat-user", "task-1", "candidate-1", new byte[] {1}, "img_cutout", 1));

        verifyNoInteractions(bot);
    }

    @Test
    void pushesFailureWithoutClaimingThatTheGarmentWasSaved() {
        ILinkBotService bot = mock(ILinkBotService.class);
        ILinkReplyContextStore contexts = new ILinkReplyContextStore();
        contexts.remember("wechat-user", "context-1");
        ILinkFashionGarmentCutoutCompletionListener listener = new ILinkFashionGarmentCutoutCompletionListener(bot, contexts);

        listener.pushFailedCutout(new FashionGarmentCutoutFailedEvent(
                "wechat-user", "task-1", "candidate-1", "图片编辑暂时没有响应，请稍后重试"));

        verify(bot).sendText(eq("wechat-user"), eq("context-1"),
                argThat(text -> text.contains("没有生成成功")
                        && text.contains("没有加入衣橱")
                        && text.contains("重新抠图")));
    }
}
