package com.example.ykdsummer.reminder.runtime;

import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.orchestration.ScheduledAgentExecutionContext;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.bot.service.ILinkBotService;
import com.example.ykdsummer.bot.service.LongTextOutputService;
import com.example.ykdsummer.common.concurrent.GracefulExecutorShutdown;
import com.example.ykdsummer.persistence.ManagedInstanceScope;
import com.example.ykdsummer.reminder.application.ReminderService;
import com.example.ykdsummer.reminder.config.ReminderProperties;
import com.example.ykdsummer.reminder.domain.ReminderDelivery;
import com.example.ykdsummer.reminder.domain.ReminderExecutionMode;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable reminder state and delivers each occurrence without occupying iLink polling threads. */
@Component
@ConditionalOnBean(ReminderService.class)
public class WechatReminderDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WechatReminderDispatcher.class);
    private final ReminderService reminders;
    private final ReminderProperties properties;
    private final ILinkReplyContextStore contexts;
    private final ILinkBotService directBot;
    private final ObjectProvider<ManagedBotInstanceManager> managedBots;
    private final AiChatService aiChatService;
    private final LongTextOutputService longTextOutputs;
    private final ExecutorService workers;

    public WechatReminderDispatcher(
            ReminderService reminders,
            ReminderProperties properties,
            ILinkReplyContextStore contexts,
            ILinkBotService directBot,
            ObjectProvider<ManagedBotInstanceManager> managedBots,
            AiChatService aiChatService,
            LongTextOutputService longTextOutputs
    ) {
        this.reminders = reminders;
        this.properties = properties;
        this.contexts = contexts;
        this.directBot = directBot;
        this.managedBots = managedBots;
        this.aiChatService = aiChatService;
        this.longTextOutputs = longTextOutputs;
        this.workers = new ThreadPoolExecutor(
                properties.getWorkerThreads(), properties.getWorkerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "#{@reminderProperties.pollInterval.toMillis()}")
    void dispatchDueReminders() {
        if (!properties.isEnabled()) return;
        Instant now = Instant.now();
        reminders.recoverExpiredDeliveries(now);
        reminders.materializeDueOccurrences(now);
        List<ReminderDelivery> deliveries = reminders.claimDueDeliveries(now);
        if (!deliveries.isEmpty()) {
            log.info("Scheduled deliveries claimed, count={}", deliveries.size());
        }
        for (ReminderDelivery delivery : deliveries) {
            try {
                workers.execute(() -> deliver(delivery));
            } catch (RejectedExecutionException exception) {
                reminders.retry(delivery.id(), delivery.attemptCount() + 1, Instant.now(), "提醒投递队列繁忙");
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverAfterApplicationStartup() {
        if (!properties.isEnabled()) return;
        int recovered = reminders.recoverInterruptedDeliveriesOnStartup();
        Instant now = Instant.now();
        int materialized = reminders.materializeDueOccurrences(now);
        log.info("Scheduled reminder recovery completed, interruptedRecovered={}, overdueMaterialized={}",
                recovered, materialized);
        try {
            workers.execute(this::dispatchDueReminders);
        } catch (RejectedExecutionException exception) {
            log.warn("Scheduled reminder recovery dispatch was not queued", exception);
        }
    }

    private void deliver(ReminderDelivery delivery) {
        Instant now = Instant.now();
        log.info("Scheduled delivery executing, delivery={}, reminder={}, mode={}, hasTaskPrompt={}, user={}",
                delivery.id(), delivery.reminderId(), delivery.executionMode(),
                delivery.taskPrompt() != null && !delivery.taskPrompt().isBlank(), anonymize(delivery.externalUserId()));
        contexts.find(delivery.externalUserId()).ifPresentOrElse(contextToken -> {
            int attempt = reminders.beginDeliveryAttempt(delivery.id());
            try {
                if (delivery.executionMode() == ReminderExecutionMode.AGENT) {
                    sendAgentResult(delivery, contextToken, executeAgentTask(delivery));
                } else {
                    sendText(delivery.externalUserId(), contextToken, "提醒：" + delivery.message());
                }
                reminders.markSent(delivery.id(), Instant.now());
                log.info("WeChat {} sent, delivery={}, user={}",
                        delivery.executionMode() == ReminderExecutionMode.AGENT ? "scheduled Agent task" : "reminder",
                        delivery.id(), anonymize(delivery.externalUserId()));
            } catch (RuntimeException exception) {
                reminders.retry(delivery.id(), attempt, Instant.now(), exception.getClass().getSimpleName());
                log.warn("WeChat reminder delivery failed, delivery={}, user={}, attempt={}", delivery.id(),
                        anonymize(delivery.externalUserId()), attempt, exception);
            }
        }, () -> reminders.waitForContext(delivery.id(), now));
    }

    private AiChatService.AssistantAnswer executeAgentTask(ReminderDelivery delivery) {
        String task = delivery.taskPrompt() == null || delivery.taskPrompt().isBlank()
                ? delivery.message() : delivery.taskPrompt().strip();
        String memoryPrompt = "[定时任务执行] " + task;
        String scheduledFor = delivery.scheduledFor().atZone(properties.zone()).toLocalDateTime().toString();
        String modelPrompt = "现在是预定任务的执行时间（原定时间：" + scheduledFor + "，中国时区）。请完成以下用户原始任务：\n"
                + task
                + "\n\n你可以自行决定直接发送提醒，或调用天气、联网、地图、图片、文件、时间等可用工具完成任务。"
                + "直接完成并给出最终结果，不要说稍后执行，也不要创建、查询或取消任何定时任务。";
        try (ScheduledAgentExecutionContext.Scope ignored = ScheduledAgentExecutionContext.enter()) {
            return aiChatService.answerWithInternalPromptRich(
                    delivery.externalUserId(), memoryPrompt, modelPrompt, List.of());
        }
    }

    private void sendAgentResult(ReminderDelivery delivery, String contextToken, AiChatService.AssistantAnswer answer) {
        for (AiArtifact artifact : answer.artifacts()) {
            if (artifact == null || artifact.bytes() == null || artifact.bytes().length == 0) continue;
            switch (artifact.type()) {
                case IMAGE -> sendImage(delivery.externalUserId(), contextToken, artifact.bytes());
                case AUDIO, DOCUMENT -> sendFile(delivery.externalUserId(), contextToken, artifact.fileName(), artifact.bytes());
            }
        }
        String text = answer.text() == null || answer.text().isBlank() ? "定时任务已完成。"
                : "定时任务结果：\n" + answer.text();
        LongTextOutputService.Delivery output = longTextOutputs.background("定时任务结果.txt", text);
        if (output.hasFile()) {
            sendFile(delivery.externalUserId(), contextToken, output.fileName(), output.bytes());
            if (!output.followUpText().isBlank()) sendText(delivery.externalUserId(), contextToken, output.followUpText());
            return;
        }
        sendText(delivery.externalUserId(), contextToken, output.text());
    }

    private void sendText(String externalUserId, String contextToken, String text) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(externalUserId);
        if (scope.managed()) {
            ManagedBotInstanceManager manager = managedBots.getIfAvailable();
            if (manager == null || !manager.sendText(externalUserId, contextToken, text)) {
                throw new IllegalStateException("Managed iLink instance is not connected");
            }
            return;
        }
        directBot.sendText(externalUserId, contextToken, text);
    }

    private void sendImage(String externalUserId, String contextToken, byte[] bytes) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(externalUserId);
        if (scope.managed()) {
            ManagedBotInstanceManager manager = managedBots.getIfAvailable();
            if (manager == null || !manager.sendImage(externalUserId, contextToken, bytes)) {
                throw new IllegalStateException("Managed iLink instance is not connected");
            }
            return;
        }
        directBot.sendImage(externalUserId, contextToken, bytes);
    }

    private void sendFile(String externalUserId, String contextToken, String fileName, byte[] bytes) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(externalUserId);
        if (scope.managed()) {
            ManagedBotInstanceManager manager = managedBots.getIfAvailable();
            if (manager == null || !manager.sendFile(externalUserId, contextToken, fileName, bytes)) {
                throw new IllegalStateException("Managed iLink instance is not connected");
            }
            return;
        }
        directBot.sendFile(externalUserId, contextToken, fileName, bytes);
    }

    @PreDestroy
    void shutdown() {
        GracefulExecutorShutdown.shutdown("wechat-reminder", Duration.ofSeconds(30), log, workers);
    }

    private static ThreadFactory daemonFactory() {
        AtomicLong sequence = new AtomicLong();
        return task -> {
            Thread thread = new Thread(task, "wechat-reminder-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
