package com.wechatbot.fashion.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wechatbot.fashion.reminder.domain.Reminder;
import com.wechatbot.fashion.reminder.domain.ReminderExecutionMode;
import com.wechatbot.fashion.reminder.domain.ReminderScheduleType;
import com.wechatbot.fashion.reminder.domain.ReminderStatus;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReminderScheduleTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void dailyScheduleMovesToTomorrowWhenTodaysTimeHasPassed() {
        Instant now = LocalDateTime.of(2026, 7, 28, 9, 0).atZone(SHANGHAI).toInstant();

        Instant next = ReminderSchedule.initialFire(ReminderScheduleType.DAILY, null, LocalTime.of(8, 30), null, SHANGHAI, now);

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 29, 8, 30).atZone(SHANGHAI).toInstant());
    }

    @Test
    void weeklyScheduleChoosesTheNextMatchingWeekday() {
        Instant now = LocalDateTime.of(2026, 7, 28, 9, 0).atZone(SHANGHAI).toInstant(); // Tuesday

        Instant next = ReminderSchedule.initialFire(ReminderScheduleType.WEEKLY, null, LocalTime.of(8, 30),
                DayOfWeek.MONDAY, SHANGHAI, now);

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 3, 8, 30).atZone(SHANGHAI).toInstant());
    }

    @Test
    void onceScheduleRejectsPastTimesAndRecurringScheduleAdvancesAfterDelivery() {
        Instant now = LocalDateTime.of(2026, 7, 28, 9, 0).atZone(SHANGHAI).toInstant();
        assertThatThrownBy(() -> ReminderSchedule.initialFire(ReminderScheduleType.ONCE,
                LocalDateTime.of(2026, 7, 28, 8, 59), null, null, SHANGHAI, now))
                .isInstanceOf(IllegalArgumentException.class);

        Reminder reminder = new Reminder("r1", "user", "喝水", ReminderExecutionMode.MESSAGE, null,
                ReminderScheduleType.DAILY, "Asia/Shanghai",
                LocalTime.of(8, 30), null, LocalDateTime.of(2026, 7, 28, 8, 30).atZone(SHANGHAI).toInstant(),
                null, ReminderStatus.ACTIVE, now);
        assertThat(ReminderSchedule.nextAfter(reminder, reminder.nextFireAt()))
                .isEqualTo(LocalDateTime.of(2026, 7, 29, 8, 30).atZone(SHANGHAI).toInstant());
    }
}
