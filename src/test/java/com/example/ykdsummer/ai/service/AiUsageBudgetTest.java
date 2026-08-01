package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.config.AiUsageProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageBudgetTest {

    @Test
    void classifiesSimpleStandardAndMultimodalRequestsWithDifferentOutputCaps() {
        AiUsageProperties properties = new AiUsageProperties();
        TokenBudgetPolicy policy = new TokenBudgetPolicy(properties);

        AiRequestBudget simple = policy.plan(List.of(), "你好", List.of(), List.of());
        AiRequestBudget standard = policy.plan(
                List.of(new ConversationMessage(ConversationMessage.Role.USER, "上一轮")),
                "请详细解释一下 Spring AI 的工具调用流程", List.of(), List.of());
        AiRequestBudget complex = policy.plan(List.of(), "请分析图片",
                List.of(new AiImage("image/png", new byte[]{1, 2, 3})), List.of());

        assertThat(simple.taskClass()).isEqualTo(AiRequestBudget.TaskClass.SIMPLE_TEXT);
        assertThat(simple.maxOutputTokens()).isEqualTo(300);
        assertThat(standard.taskClass()).isEqualTo(AiRequestBudget.TaskClass.STANDARD);
        assertThat(standard.maxOutputTokens()).isEqualTo(600);
        assertThat(complex.taskClass()).isEqualTo(AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL);
        assertThat(complex.maxOutputTokens()).isEqualTo(1_000);
    }

    @Test
    void classifiesShortRoutePlanningRequestsAsComplex() {
        AiUsageProperties properties = new AiUsageProperties();
        TokenBudgetPolicy policy = new TokenBudgetPolicy(properties);

        AiRequestBudget routePlanning = policy.plan(List.of(), "从北京南站到故宫怎么走", List.of(), List.of());

        assertThat(routePlanning.taskClass()).isEqualTo(AiRequestBudget.TaskClass.COMPLEX_OR_MULTIMODAL);
        assertThat(routePlanning.maxOutputTokens()).isEqualTo(1_000);
    }

    @Test
    void disablesOutputCapsAndQuotaPlanningWhenUsageProtectionIsOff() {
        AiUsageProperties properties = new AiUsageProperties();
        properties.setEnabled(false);

        AiRequestBudget budget = new TokenBudgetPolicy(properties).plan(
                List.of(), "从北京南站到故宫怎么走", List.of(), List.of());

        assertThat(budget.maxOutputTokens()).isZero();
    }

    @Test
    void blocksOversizedFilesBeforeAnyModelRequest() {
        AiUsageProperties properties = new AiUsageProperties();
        properties.setMaxEstimatedInputTokens(100);
        AiUsageMeter meter = new AiUsageMeter(properties, fixedClock());
        AiRequestBudget budget = new TokenBudgetPolicy(properties).plan(List.of(), "转换文件", List.of(),
                List.of(new AiFile("large.txt", "text/plain", new byte[2_000])));

        AiUsageMeter.Reservation reservation = meter.reserve("u-1", budget);

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.rejectReason()).isEqualTo(AiUsageMeter.RejectReason.INPUT_TOO_LARGE);
    }

    @Test
    void settlesReportedUsageAndUsesReservedEstimateOnlyWhenGatewayOmitsUsage() {
        AiUsageProperties properties = new AiUsageProperties();
        properties.setDailyTokenLimit(1_000);
        AiUsageMeter meter = new AiUsageMeter(properties, fixedClock());
        AiRequestBudget first = new AiRequestBudget(AiRequestBudget.TaskClass.SIMPLE_TEXT, 300, 50, 350);
        AiRequestBudget second = new AiRequestBudget(AiRequestBudget.TaskClass.SIMPLE_TEXT, 300, 40, 340);

        AiUsageMeter.Reservation firstReservation = meter.reserve("u-1", first);
        meter.complete(firstReservation, "chat-completions", "model-a", AiModelUsage.reported(10, 20, 30));
        AiUsageMeter.Reservation secondReservation = meter.reserve("u-1", second);
        meter.complete(secondReservation, "responses", "model-b", AiModelUsage.unknown());

        AiUsageMeter.UsageSnapshot snapshot = meter.snapshot("u-1");
        assertThat(snapshot.reportedTotalTokens()).isEqualTo(30);
        assertThat(snapshot.estimatedFallbackTokens()).isEqualTo(340);
        assertThat(snapshot.accountedTokens()).isEqualTo(370);
        assertThat(snapshot.reservedTokens()).isZero();
        assertThat(snapshot.requestsByProtocol()).containsEntry("chat-completions", 1L).containsEntry("responses", 1L);
    }

    @Test
    void releasesFailedRequestReservationSoItDoesNotConsumeDailyBudget() {
        AiUsageProperties properties = new AiUsageProperties();
        properties.setDailyTokenLimit(400);
        AiUsageMeter meter = new AiUsageMeter(properties, fixedClock());
        AiRequestBudget budget = new AiRequestBudget(AiRequestBudget.TaskClass.SIMPLE_TEXT, 300, 50, 350);

        AiUsageMeter.Reservation failed = meter.reserve("u-1", budget);
        meter.release(failed);
        AiUsageMeter.Reservation retry = meter.reserve("u-1", budget);

        assertThat(failed.allowed()).isTrue();
        assertThat(retry.allowed()).isTrue();
        assertThat(meter.snapshot("u-1").reservedTokens()).isEqualTo(350);
    }

    @Test
    void chatServicePassesBudgetToGatewayAndUsesReportedUsageForTheUser() {
        AiProperties aiProperties = new AiProperties();
        AiUsageProperties usageProperties = new AiUsageProperties();
        AiUsageMeter meter = new AiUsageMeter(usageProperties, fixedClock());
        BudgetRecordingGateway gateway = new BudgetRecordingGateway();
        AiChatService service = new AiChatService(
                aiProperties, gateway, AiTraceLogger.disabled(), meter, new TokenBudgetPolicy(usageProperties));

        assertThat(service.answer("u-1", "你好", List.of())).isEqualTo("收到");
        assertThat(gateway.lastBudget.maxOutputTokens()).isEqualTo(300);
        assertThat(meter.snapshot("u-1").reportedTotalTokens()).isEqualTo(12);
    }

    @Test
    void recordsSuccessfulAndFailedModelAttemptsWithTheirDurations() {
        AiUsageProperties properties = new AiUsageProperties();
        AiUsageMeter meter = new AiUsageMeter(properties, fixedClock());
        RecordingUsageEvents events = new RecordingUsageEvents();
        meter.setEventRecorder(events);
        AiRequestBudget budget = new AiRequestBudget(AiRequestBudget.TaskClass.SIMPLE_TEXT, 10, 20, 30);

        meter.complete(meter.reserve("managed:instance:user-a", budget), "chat-completions", "qwen-test",
                AiModelUsage.reported(4, 6, 10), 345);
        meter.fail(meter.reserve("managed:instance:user-a", budget), "chat-completions", "",
                "TEMPORARY_UNAVAILABLE", 678);

        assertThat(events.successDurations).containsExactly(345L);
        assertThat(events.failureDurations).containsExactly(678L);
        assertThat(events.failureReasons).containsExactly("TEMPORARY_UNAVAILABLE");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-23T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private static final class BudgetRecordingGateway implements LlmGateway {
        private AiRequestBudget lastBudget;

        @Override
        public ModelReply generate(
                List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files
        ) {
            return new ModelReply("不应走到这个默认方法", "test");
        }

        @Override
        public ModelReply generate(
                String userId, List<ConversationMessage> history, String prompt,
                List<AiImage> images, List<AiFile> files, AiRequestBudget budget
        ) {
            this.lastBudget = budget;
            return new ModelReply("收到", "test-model", List.of(),
                    AiModelUsage.reported(5, 7, 12), "chat-completions");
        }
    }

    private static final class RecordingUsageEvents implements UsageEventRecorder {
        private final List<Long> successDurations = new ArrayList<>();
        private final List<Long> failureDurations = new ArrayList<>();
        private final List<String> failureReasons = new ArrayList<>();

        @Override
        public void recordModel(String userId, String protocol, String model, AiModelUsage usage, long estimatedTotalTokens) { }

        @Override
        public void recordModel(String userId, String protocol, String model, AiModelUsage usage,
                                long estimatedTotalTokens, long durationMs) {
            successDurations.add(durationMs);
        }

        @Override
        public void recordModelFailure(String userId, String protocol, String model, String failureReason, long durationMs) {
            failureDurations.add(durationMs);
            failureReasons.add(failureReason);
        }

        @Override
        public void recordOperation(String userId, String kind, String model, String toolName, long quantity, long durationMs) { }

        @Override
        public void recordTool(String userId, String toolName, boolean succeeded, long durationMs) { }
    }
}
