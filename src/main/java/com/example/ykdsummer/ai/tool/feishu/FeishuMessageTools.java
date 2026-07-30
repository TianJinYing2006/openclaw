package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 飞书消息工具集。支持发送文本消息给用户或群聊。
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuMessageTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageTools.class);

    private final FeishuClient feishuClient;

    public FeishuMessageTools(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Tool(
            name = "feishu_message_send",
            description = "向飞书用户或群聊发送文本消息。"
                    + "支持指定接收者、群聊 ID、以及 @ 指定用户"
    )
    public String sendMessage(
            @ToolParam(required = true, description = "接收者 ID（用户 open_id 或 chat_id）") String receiveId,
            @ToolParam(required = true, description = "消息文本内容") String text,
            @ToolParam(required = false, description = "ID 类型：open_id（用户）/ chat_id（群聊），默认 open_id") String receiveType,
            @ToolParam(required = false, description = "要 @ 的用户 open_id 列表，英文逗号分隔") String atUserIds
    ) {
        if (!feishuClient.isAvailable()) {
            return "飞书未配置，无法发送消息";
        }
        try {
            String type = (receiveType != null && !receiveType.isBlank()) ? receiveType : "open_id";
            String finalText = text;

            // 处理 @ 用户
            if (atUserIds != null && !atUserIds.isBlank()) {
                StringBuilder sb = new StringBuilder();
                for (String uid : atUserIds.split(",")) {
                    uid = uid.strip();
                    if (!uid.isEmpty()) {
                        sb.append("<at user_id=\"").append(uid).append("\"></at>");
                    }
                }
                sb.append(" ").append(text);
                finalText = sb.toString();
            }

            log.info("Feishu sendMessage: receiveId={}, type={}", receiveId, type);
            String messageId = feishuClient.sendMessage(receiveId, type, finalText);
            return "消息已发送，消息ID: " + messageId;
        } catch (FeishuApiException e) {
            log.warn("Feishu sendMessage failed: {}", e.getMessage());
            return "发送消息失败：" + e.getMessage();
        }
    }
}
