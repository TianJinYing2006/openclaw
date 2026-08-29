package com.example.ykdsummer.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.fashion.wardrobe.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.wardrobe.runtime.FashionOutfitRecommendationCompletedEvent;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ILinkFashionOutfitRecommendationCompletionListenerTest {

    @Test
    void proactivelySendsTheRankedBoardWithoutInternalIdentifiers() {
        ILinkBotService bot = mock(ILinkBotService.class);
        ILinkReplyContextStore contexts = mock(ILinkReplyContextStore.class);
        when(contexts.find("wechat-user")).thenReturn(Optional.of("reply-context"));
        ILinkFashionOutfitRecommendationCompletionListener listener =
                new ILinkFashionOutfitRecommendationCompletionListener(bot, contexts);

        listener.pushCompletedOutfit(new FashionOutfitRecommendationCompletedEvent(
                "wechat-user", "internal-run", "internal-option", 2,
                "白色短袖 + 深蓝直筒裤", OutfitRenderStatus.FALLBACK,
                new byte[] {1, 2, 3}, "img_result", 1));

        verify(bot).sendImage(eq("wechat-user"), eq("reply-context"), eq(new byte[] {1, 2, 3}));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot).sendText(eq("wechat-user"), eq("reply-context"), text.capture());
        assertThat(text.getValue())
                .contains("第 2 套", "白色短袖 + 深蓝直筒裤", "真实衣橱单品拼接")
                .doesNotContain("internal-run", "internal-option", "img_result");
    }
}
