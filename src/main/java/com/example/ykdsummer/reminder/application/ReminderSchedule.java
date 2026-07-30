package com.example.ykdsummer.reminder.application;

import com.example.ykdsummer.reminder.domain.Reminder;
import com.example.ykdsummer.reminder.domain.ReminderScheduleType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Calendar calculation is isolated from persistence and delivery. */
final class ReminderSchedule {
    private ReminderSchedule() { }

    static Instant initialFire(ReminderScheduleType type, LocalDateTime onceAt, LocalTime timeOfDay,
                               DayOfWeek weekday, ZoneId zone, Instant now) {
        return switch (type) {
            case ONCE -> requireFuture(onceAt, zone, now, "一次性提醒时间必须晚于当前时间");
            case DAILY -> nextDaily(timeOfDay, zone, now);
            case WEEKLY -> nextWeekly(timeOfDay, weekday, zone, now);
        };
    }

    static Instant nextAfter(Reminder reminder, Instant deliveredAt) {
        ZoneId zone = ZoneId.of(reminder.zoneId());
        return switch (reminder.scheduleType()) {
            case ONCE -> null;
            case DAILY -> nextDaily(requiredTime(reminder), zone, deliveredAt.plusSeconds(1));
            case WEEKLY -> nextWeekly(requiredTime(reminder), requiredWeekday(reminder), zone, deliveredAt.plusSeconds(1));
        };
    }

    private static Instant requireFuture(LocalDateTime value, ZoneId zone, Instant now, String message) {
        if (value == null) throw new IllegalArgumentException("一次性提醒需要完整日期和时间，格式为 yyyy-MM-dd HH:mm");
        Instant instant = value.atZone(zone).toInstant();
        if (!instant.isAfter(now)) throw new IllegalArgumentException(message);
        return instant;
    }

    private static Instant nextDaily(LocalTime time, ZoneId zone, Instant now) {
        LocalTime safeTime = requiredTime(time);
        ZonedDateTime current = now.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(current.toLocalDate(), safeTime, zone);
        return (candidate.isAfter(current) ? candidate : candidate.plusDays(1)).toInstant();
    }

    private static Instant nextWeekly(LocalTime time, DayOfWeek weekday, ZoneId zone, Instant now) {
        LocalTime safeTime = requiredTime(time);
        if (weekday == null) throw new IllegalArgumentException("每周提醒需要指定星期，例如 MONDAY");
        ZonedDateTime current = now.atZone(zone);
        LocalDate date = current.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday));
        ZonedDateTime candidate = ZonedDateTime.of(date, safeTime, zone);
        return (candidate.isAfter(current) ? candidate : candidate.plusWeeks(1)).toInstant();
    }

    private static LocalTime requiredTime(Reminder reminder) {
        return requiredTime(reminder.localTime());
    }

    private static LocalTime requiredTime(LocalTime value) {
        if (value == null) throw new IllegalArgumentException("重复提醒需要时间，格式为 HH:mm");
        return value;
    }

    private static DayOfWeek requiredWeekday(Reminder reminder) {
        if (reminder.weekday() == null || reminder.weekday() < 1 || reminder.weekday() > 7) {
            throw new IllegalArgumentException("每周提醒缺少有效星期");
        }
        return DayOfWeek.of(reminder.weekday());
    }
}
