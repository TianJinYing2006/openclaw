package com.wechatbot.fashion.ai.fashion.look;

import java.util.regex.Pattern;

/**
 * 判断一次穿搭请求是否属于「触发付费/高费用操作、执行前应向用户确认」的意图。
 *
 * <p>当前覆盖付费图片类操作（生成试穿图 / 生成图片 / 改图）。采用保守的关键词规则，
 * 宁可漏判（不弹确认）也不误判（打扰普通咨询）；HITL 默认关闭，需
 * {@code app.fashion.graph.hitl.enabled=true} 才生效。
 */
public final class ConfirmationIntent {

    private static final Pattern PAID_OPERATION = Pattern.compile(
            "生成.{0,6}(试穿|上身|效果图|图片|图)|试穿图|上身图|帮我生成|生成一张|合成图|p图|P图|改图|修图|改一下|修一下");

    private ConfirmationIntent() {
    }

    /** 是否需要用户确认后再执行（付费操作意图）。 */
    public static boolean requiresConfirmation(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return PAID_OPERATION.matcher(query).find();
    }
}
