package com.wechatbot.fashion.reminder.persistence;

import com.wechatbot.fashion.reminder.domain.Reminder;
import com.wechatbot.fashion.reminder.domain.ReminderDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ReminderRepository {
    Reminder create(Reminder reminder);
    List<Reminder> activeDue(Instant now, int limit);
    void materializeDueReminder(Reminder reminder, Instant nextFireAt);
    List<ReminderDelivery> claimDueDeliveries(Instant now, int limit);
    int beginDeliveryAttempt(String deliveryId);
    void markSent(String deliveryId, Instant sentAt);
    void markWaitingForContext(String deliveryId, Instant nextAttemptAt);
    void markRetry(String deliveryId, Instant nextAttemptAt, String failureSummary);
    void markFailed(String deliveryId, String failureSummary);
    void recoverExpiredProcessing(Instant claimedBefore);
    int recoverInterruptedProcessingOnStartup();
    List<Reminder> listForUser(String externalUserId, int limit);
    boolean cancel(String externalUserId, String reminderId);
}
