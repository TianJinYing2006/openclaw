package com.example.ykdsummer.bot.message;

import io.github.morningwn.protocol.ProtocolValues;

/**
 * 本项目使用的消息类型名称。
 *
 * <p>iLink 协议/SDK 返回的是整数类型码，例如 {@link ProtocolValues#ITEM_TYPE_TEXT}。
 * 业务代码如果到处直接比较数字会很难阅读，因此先在这里翻译成 TEXT、IMAGE 等枚举。
 * 这只是本地分类，不会改变 SDK 收到的原始消息。</p>
 */
public enum ILinkMessageType {
    TEXT,
    IMAGE,
    VOICE,
    FILE,
    VIDEO,
    UNKNOWN;

    /**
     * 将 SDK 的整数类型码转换为本项目枚举。
     *
     * @param sdkType {@code MessageItem.type()} 返回的协议类型码
     * @return 已知类型；空值或新版本中尚未适配的类型统一返回 UNKNOWN
     */
    public static ILinkMessageType from(Integer sdkType) {
        if (sdkType == null) {
            return UNKNOWN;
        }
        return switch (sdkType) {
            case ProtocolValues.ITEM_TYPE_TEXT -> TEXT;
            case ProtocolValues.ITEM_TYPE_IMAGE -> IMAGE;
            case ProtocolValues.ITEM_TYPE_VOICE -> VOICE;
            case ProtocolValues.ITEM_TYPE_FILE -> FILE;
            case ProtocolValues.ITEM_TYPE_VIDEO -> VIDEO;
            default -> UNKNOWN;
        };
    }
}
