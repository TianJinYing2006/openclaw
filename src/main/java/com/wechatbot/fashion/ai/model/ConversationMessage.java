package com.wechatbot.fashion.ai.model;

/**
 * 本地多轮对话中保存的一条纯文字消息。USER 是微信用户说的话，ASSISTANT 是模型回答。
 * 本项目不把图片二进制保存在这里，避免聊天历史长期占用大量内存。
 */
public record ConversationMessage(Role role, String text) {

    public enum Role {
        USER,
        ASSISTANT
    }
}
