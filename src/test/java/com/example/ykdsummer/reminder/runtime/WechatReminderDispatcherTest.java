package com.example.ykdsummer.reminder.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.bot.service.ILinkBotService;
import com.example.ykdsummer.bot.service.LongTextOutputService;
import com.example.ykdsummer.bot.config.LongTextOutputProperties;
import com.example.ykdsummer.reminder.application.ReminderService;
import com.example.ykdsummer.reminder.config.ReminderProperties;
import com.example.ykdsummer.reminder.domain.ReminderDelivery;
import com.example.ykdsummer.reminder.domain.ReminderDeliveryStatus;
import com.example.ykdsummer.reminder.domain.ReminderExecutionMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WechatReminderDispatcherTest {

    @Test
    void dueDeliveryUsesSavedContextToSendAnIndependentWeChatMessage() {
        ReminderService reminders = mock(ReminderService.class);
        ILinkBotService bot = mock(ILinkBotService.class);
        AiChatService aiChat = mock(AiChatService.class);
        ILinkReplyContextStore contexts = new ILinkReplyContextStore();
        contexts.remember("wechat-user", "context-token");
        ReminderDelivery delivery = new ReminderDelivery("delivery-1", "reminder-1", "wechat-user", Instant.now(),
                "记得喝水", ReminderExecutionMode.MESSAGE, null, ReminderDeliveryStatus.PENDING, 0);
        when(reminders.claimDueDeliveries(any())).thenReturn(List.of(delivery));
        when(reminders.beginDeliveryAttempt("delivery-1")).thenReturn(1);
        @SuppressWarnings("unchecked")
        ObjectProvider<ManagedBotInstanceManager> managed = mock(ObjectProvider.class);
        ReminderProperties properties = new ReminderProperties();
        WechatReminderDispatcher dispatcher = new WechatReminderDispatcher(reminders, properties, contexts, bot, managed,
                aiChat, new LongTextOutputService(new LongTextOutputProperties()));

        try {
            dispatcher.dispatchDueReminders();

            verify(bot, timeout(1000)).sendText("wechat-user", "context-token", "提醒：记得喝水");
            verify(reminders, timeout(1000)).markSent(eq("delivery-1"), any());
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    void dueAgentTaskReentersTheAgentAndPushesItsFinalAnswer() {
        ReminderService reminders = mock(ReminderService.class);
        ILinkBotService bot = mock(ILinkBotService.class);
        AiChatService aiChat = mock(AiChatService.class);
        ILinkReplyContextStore contexts = new ILinkReplyContextStore();
        contexts.remember("wechat-user", "context-token");
        ReminderDelivery delivery = new ReminderDelivery("delivery-2", "reminder-2", "wechat-user", Instant.now(),
                "查询杭州天气", ReminderExecutionMode.AGENT, "查询杭州天气", ReminderDeliveryStatus.PENDING, 0);
        when(reminders.claimDueDeliveries(any())).thenReturn(List.of(delivery));
        when(reminders.beginDeliveryAttempt("delivery-2")).thenReturn(1);
        when(aiChat.answerWithInternalPromptRich(eq("wechat-user"), contains("定时任务执行"),
                contains("现在是预定任务的执行时间"), anyList()))
                .thenReturn(AiChatService.AssistantAnswer.text("杭州晴，适合出行。"));
        @SuppressWarnings("unchecked")
        ObjectProvider<ManagedBotInstanceManager> managed = mock(ObjectProvider.class);
        WechatReminderDispatcher dispatcher = new WechatReminderDispatcher(reminders, new ReminderProperties(), contexts,
                bot, managed, aiChat, new LongTextOutputService(new LongTextOutputProperties()));

        try {
            dispatcher.dispatchDueReminders();

            verify(aiChat, timeout(1000)).answerWithInternalPromptRich(eq("wechat-user"), contains("查询杭州天气"),
                    contains("不要创建、查询或取消任何定时任务"), anyList());
            verify(bot, timeout(1000)).sendText("wechat-user", "context-token", "定时任务结果：\n杭州晴，适合出行。");
            verify(reminders, timeout(1000)).markSent(eq("delivery-2"), any());
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    void startupImmediatelyRecoversInterruptedTasksAndScansOverdueSchedules() {
        ReminderService reminders = mock(ReminderService.class);
        ILinkBotService bot = mock(ILinkBotService.class);
        AiChatService aiChat = mock(AiChatService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ManagedBotInstanceManager> managed = mock(ObjectProvider.class);
        when(reminders.recoverInterruptedDeliveriesOnStartup()).thenReturn(2);
        when(reminders.materializeDueOccurrences(any())).thenReturn(1);
        when(reminders.claimDueDeliveries(any())).thenReturn(List.of());
        WechatReminderDispatcher dispatcher = new WechatReminderDispatcher(reminders, new ReminderProperties(),
                new ILinkReplyContextStore(), bot, managed, aiChat,
                new LongTextOutputService(new LongTextOutputProperties()));

        try {
            dispatcher.recoverAfterApplicationStartup();

            verify(reminders).recoverInterruptedDeliveriesOnStartup();
            verify(reminders, timeout(1000).atLeastOnce()).materializeDueOccurrences(any());
        } finally {
            dispatcher.shutdown();
        }
    }
}
