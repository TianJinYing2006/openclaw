package com.example.ykdsummer.schedule.handler;

import com.example.ykdsummer.bot.service.ILinkBotService;
import com.example.ykdsummer.schedule.model.ScheduledTask;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 定时提醒处理器：在任务触发时通过 iLink SDK 向用户发送提醒文字。
 *
 * <p>任务 {@code params} 应为 JSON 格式，包含以下字段：</p>
 * <ul>
 *   <li>{@code toUserId} — 微信用户 ID（iLink fromUserId）</li>
 *   <li>{@code contextToken} — 会话上下文令牌</li>
 *   <li>{@code text} — 提醒文字</li>
 * </ul>
 *
 * <p>创建定时提醒任务时，handlerBean 填写 {@code "reminderTaskHandler"}。</p>
 */
@Component("reminderTaskHandler")
public class ReminderTaskHandler implements ScheduledTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ReminderTaskHandler.class);

    private final ILinkBotService botService;
    private final ObjectMapper objectMapper;

    public ReminderTaskHandler(ILinkBotService botService, ObjectMapper objectMapper) {
        this.botService = botService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(ScheduledTask task) {
        String paramsJson = task.getParams();
        if (paramsJson == null || paramsJson.isBlank()) {
            log.warn("Reminder task {} has empty params, skipping", task.getId());
            return;
        }

        try {
            Map<String, String> params = objectMapper.readValue(paramsJson,
                    new TypeReference<Map<String, String>>() {});

            String toUserId = params.get("toUserId");
            String contextToken = params.get("contextToken");
            String text = params.get("text");

            if (toUserId == null || toUserId.isBlank()) {
                log.warn("Reminder task {} missing toUserId", task.getId());
                return;
            }
            if (text == null || text.isBlank()) {
                log.warn("Reminder task {} missing text", task.getId());
                return;
            }

            String effectiveContextToken = contextToken != null && !contextToken.isBlank()
                    ? contextToken
                    : "unknown";

            botService.sendText(toUserId, effectiveContextToken, text);
            log.info("Reminder sent to user={}, taskId={}, text='{}'",
                    anonymize(toUserId), task.getId(), truncate(text, 60));

        } catch (Exception e) {
            log.error("Failed to send reminder for task {}", task.getId(), e);
            // 异常由调度器捕获并记录到 DB（FAILED）
            throw new RuntimeException("Reminder sending failed: " + e.getMessage(), e);
        }
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }

    private static String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen
                ? text.substring(0, maxLen) + "…"
                : text;
    }
}
