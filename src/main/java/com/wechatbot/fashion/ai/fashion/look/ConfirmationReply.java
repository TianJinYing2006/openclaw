package com.wechatbot.fashion.ai.fashion.look;

import java.util.List;
import java.util.Locale;

/**
 * 解析用户对 HITL 确认请求的回复：确认 / 取消 / 无法识别。
 *
 * <p>只做确定性的短回复匹配；无法识别时返回 {@code null}，由调用方继续追问，
 * 避免把「嗯」「哦」等模糊回复误判为确认而执行付费操作。
 */
public final class ConfirmationReply {

    private static final List<String> AFFIRMATIVE = List.of(
            "确认", "确定", "可以", "好的", "好", "是", "继续", "嗯嗯", "要", "yes", "y", "ok", "okay");
    private static final List<String> NEGATIVE = List.of(
            "取消", "不用", "不要", "算了", "否", "不", "no", "n");

    private ConfirmationReply() {
    }

    /** @return TRUE=确认，FALSE=取消，null=无法识别。 */
    public static Boolean parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String normalized = reply.strip().toLowerCase(Locale.ROOT);
        if (AFFIRMATIVE.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (NEGATIVE.contains(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
