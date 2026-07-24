package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RealtimeQueryContextTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T05:42:00Z"), ZoneOffset.UTC);

    @Test
    void addsTimeAwareSearchGuidanceForImplicitCurrentEventQuestion() {
        String context = RealtimeQueryContext.systemContext("这一届世界杯冠军是谁", CLOCK);

        assertThat(context)
                .contains("当前北京时间：2026-07-24 13:42")
                .contains("这一届", "web_search", "不是强制调用");
    }

    @Test
    void keepsOrdinaryQuestionsModelDirected() {
        String context = RealtimeQueryContext.systemContext("解释一下二分查找", CLOCK);

        assertThat(context)
                .contains("当前北京时间：2026-07-24 13:42")
                .doesNotContain("本轮包含时效表达", "web_search");
    }
}
