package com.example.ykdsummer.fashion.wardrobe.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.fashion.wardrobe.domain.WardrobeSearchCriteria;
import java.util.List;
import org.junit.jupiter.api.Test;

class FashionWardrobeVisualCommandHandlerTest {

    @Test
    void directlyPreparesImagesForAnExplicitCurrentWardrobePictureRequest() {
        FashionVisualPreviewTools previews = mock(FashionVisualPreviewTools.class);
        when(previews.prepareWardrobeDisplay(eq("wechat-user"), any(WardrobeSearchCriteria.class), eq(8)))
                .thenReturn(new FashionVisualPreviewTools.WardrobeDisplay("已发衣橱展示图。",
                        List.of(AiArtifact.image(new byte[]{1}, "第1页", "", 0))));
        FashionWardrobeVisualCommandHandler handler = new FashionWardrobeVisualCommandHandler(previews);

        var answer = handler.handle("wechat-user", "查看我目前的衣橱图片").orElseThrow();

        assertThat(answer.text()).isEqualTo("已发衣橱展示图。");
        assertThat(answer.artifacts()).singleElement();
        verify(previews).prepareWardrobeDisplay(eq("wechat-user"), any(WardrobeSearchCriteria.class), eq(8));
    }

    @Test
    void leavesTextOnlyWardrobeQueriesToTheAgent() {
        FashionVisualPreviewTools previews = mock(FashionVisualPreviewTools.class);
        FashionWardrobeVisualCommandHandler handler = new FashionWardrobeVisualCommandHandler(previews);

        assertThat(handler.handle("wechat-user", "我衣橱里有哪些衣服")).isEmpty();
    }

    @Test
    void recognizesAShortDisplayFollowUpAfterWardrobeContext() {
        FashionVisualPreviewTools previews = mock(FashionVisualPreviewTools.class);
        when(previews.prepareWardrobeDisplay(eq("wechat-user"), any(WardrobeSearchCriteria.class), eq(8)))
                .thenReturn(new FashionVisualPreviewTools.WardrobeDisplay("已发衣橱展示图。", List.of()));
        FashionWardrobeVisualCommandHandler handler = new FashionWardrobeVisualCommandHandler(previews);

        assertThat(handler.handle("wechat-user", "帮我展示下", List.of(
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "目前你的衣橱里有 1 件衣服。"))))
                .isPresent();
        assertThat(handler.handle("wechat-user", "帮我展示下", List.of())).isEmpty();
    }
}
