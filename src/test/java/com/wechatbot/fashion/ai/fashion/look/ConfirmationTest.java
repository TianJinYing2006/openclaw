package com.wechatbot.fashion.ai.fashion.look;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HITL 意图识别与答复解析（确定性规则）守门测试。 */
class ConfirmationTest {

    @Test
    void detectsPaidOperationIntent() {
        assertThat(ConfirmationIntent.requiresConfirmation("帮我生成一张试穿图")).isTrue();
        assertThat(ConfirmationIntent.requiresConfirmation("给我生成上身效果")).isTrue();
        assertThat(ConfirmationIntent.requiresConfirmation("帮我把这张图改一下")).isTrue();
        assertThat(ConfirmationIntent.requiresConfirmation("生成图片")).isTrue();
    }

    @Test
    void regularConsultationDoesNotNeedConfirmation() {
        assertThat(ConfirmationIntent.requiresConfirmation("明天面试穿什么")).isFalse();
        assertThat(ConfirmationIntent.requiresConfirmation("推荐一套海边度假穿搭")).isFalse();
        assertThat(ConfirmationIntent.requiresConfirmation("")).isFalse();
        assertThat(ConfirmationIntent.requiresConfirmation(null)).isFalse();
    }

    @Test
    void parsesAffirmativeAndNegativeReplies() {
        assertThat(ConfirmationReply.parse("确认")).isTrue();
        assertThat(ConfirmationReply.parse(" 好的 ")).isTrue();
        assertThat(ConfirmationReply.parse("OK")).isTrue();
        assertThat(ConfirmationReply.parse("取消")).isFalse();
        assertThat(ConfirmationReply.parse("不用")).isFalse();
    }

    @Test
    void ambiguousReplyIsNotTreatedAsApproval() {
        assertThat(ConfirmationReply.parse("嗯")).isNull();
        assertThat(ConfirmationReply.parse("再说吧")).isNull();
        assertThat(ConfirmationReply.parse(null)).isNull();
    }
}
