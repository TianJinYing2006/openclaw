package com.wechatbot.fashion.ai.fashion.look.rag;

import com.wechatbot.fashion.ai.fashion.look.agent.AgentLlmCaller;
import com.wechatbot.fashion.ai.fashion.look.agent.AgentPrompts;
import com.wechatbot.fashion.ai.fashion.look.model.FeedbackDetection;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 反馈检测回归：混合反馈（"还行但裤子不太行"）应被识别为 MIXED；LLM 失败时回退关键词。
 */
class QueryAnalyzerFeedbackDetectionTest {

    private final AgentLlmCaller llmCaller = mock(AgentLlmCaller.class);

    @Test
    void recognizesMixedFeedback() {
        when(llmCaller.callAgent(eq(AgentPrompts.FEEDBACK_DETECTOR), eq("还行但裤子不太行"),
                eq(FeedbackDetection.class), anyInt(), any(Duration.class)))
                .thenReturn(new FeedbackDetection(true, "MIXED"));

        FeedbackDetection detection = new QueryAnalyzer(llmCaller, null, true).detectFeedback("还行但裤子不太行");

        assertThat(detection.isFeedback()).isTrue();
        assertThat(detection.sentiment()).isEqualTo("MIXED");
    }

    @Test
    void recognizesPositiveFeedback() {
        when(llmCaller.callAgent(eq(AgentPrompts.FEEDBACK_DETECTOR), eq("这套我很喜欢"),
                eq(FeedbackDetection.class), anyInt(), any(Duration.class)))
                .thenReturn(new FeedbackDetection(true, "POSITIVE"));

        FeedbackDetection detection = new QueryAnalyzer(llmCaller, null, true).detectFeedback("这套我很喜欢");

        assertThat(detection.isFeedback()).isTrue();
        assertThat(detection.sentiment()).isEqualTo("POSITIVE");
    }

    @Test
    void treatsNewFashionRequestAsNotFeedback() {
        when(llmCaller.callAgent(eq(AgentPrompts.FEEDBACK_DETECTOR), eq("帮我搭一套面试穿搭"),
                eq(FeedbackDetection.class), anyInt(), any(Duration.class)))
                .thenReturn(FeedbackDetection.notFeedback());

        FeedbackDetection detection = new QueryAnalyzer(llmCaller, null, true).detectFeedback("帮我搭一套面试穿搭");

        assertThat(detection.isFeedback()).isFalse();
    }

    @Test
    void fallsBackToKeywordsWhenLlmFails() {
        when(llmCaller.callAgent(eq(AgentPrompts.FEEDBACK_DETECTOR), eq("不太喜欢这套"),
                eq(FeedbackDetection.class), anyInt(), any(Duration.class)))
                .thenReturn(null);

        FeedbackDetection detection = new QueryAnalyzer(llmCaller, null, true).detectFeedback("不太喜欢这套");

        assertThat(detection.isFeedback()).isTrue();
        assertThat(detection.sentiment()).isEqualTo("UNKNOWN");
    }

    @Test
    void blankInputIsNeverFeedback() {
        FeedbackDetection detection = new QueryAnalyzer(llmCaller, null, true).detectFeedback("  ");

        assertThat(detection.isFeedback()).isFalse();
    }
}
