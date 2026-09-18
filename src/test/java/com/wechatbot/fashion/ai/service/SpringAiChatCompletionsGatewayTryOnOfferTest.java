package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.model.ConversationMessage;
import com.wechatbot.fashion.ai.model.ConversationMessage.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 试穿漏调兜底：识别「机器人邀约试穿 + 用户简短肯定」。 */
class SpringAiChatCompletionsGatewayTryOnOfferTest {

    private static List<ConversationMessage> history(String assistantText) {
        return List.of(
                new ConversationMessage(Role.USER, "推荐一套通勤穿搭"),
                new ConversationMessage(Role.ASSISTANT, assistantText));
    }

    @Test
    void affirmativeAfterTryOnOfferIsDetected() {
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(
                "好呀", history("需要试穿看看效果吗？"))).isTrue();
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(
                "可以", history("要不要我帮你生成上身效果图？"))).isTrue();
    }

    @Test
    void affirmativeWithoutTryOnOfferIsNotDetected() {
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(
                "好呀", history("这是你的推荐方案，参考图已发送。"))).isFalse();
    }

    @Test
    void nonAffirmativeMessageIsNotDetected() {
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(
                "帮我推荐一套", history("需要试穿看看效果吗？"))).isFalse();
    }

    @Test
    void longSentenceStartingWithAffirmativeIsNotDetected() {
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(
                "好呀，再帮我看看红色的那件", history("需要试穿看看效果吗？"))).isFalse();
    }

    @Test
    void emptyHistoryIsNotDetected() {
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer("好呀", List.of())).isFalse();
        assertThat(SpringAiChatCompletionsGateway.isAffirmativeToTryOnOffer(null, List.of())).isFalse();
    }
}
