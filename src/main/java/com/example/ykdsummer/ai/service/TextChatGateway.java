package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.ConversationMessage;

import java.util.List;

/**
 * 普通纯文本聊天的协议边界。
 *
 * <p>当前实现使用 Spring AI Chat Completions。接口单独存在，是为了让后续 Agent/Tool
 * 复用同一条 Spring AI 通道，同时不把文件和图片强行改成 Completion 格式。</p>
 */
public interface TextChatGateway {

    LlmGateway.ModelReply generate(List<ConversationMessage> history, String prompt);
}
