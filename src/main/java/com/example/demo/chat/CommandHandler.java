package com.example.demo.chat;

import com.example.demo.ai.LLMService;
import com.example.demo.chat.CommandManager;
import com.example.demo.chat.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 消息处理器 — 优先匹配命令，否则交给带会话记忆的 LLM 处理
 * <p>支持纯文本和图片多模态输入。</p>
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private static final String IMAGE_PLACEHOLDER = "用户发送了一张图片";

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Autowired
    @Lazy
    private LLMService llmService;

    @Autowired
    private SessionManager sessionManager;

    /**
     * 处理纯文本消息
     * <ul>
     *   <li>以 {@code /} 开头的文本 → 交给 CommandManager 分发（不入历史）</li>
     *   <li>其他文本 → 通过 SessionManager 记录历史，再调用 LLM（回复也记入历史）</li>
     * </ul>
     */
    public String handle(String text, String fromUserId) {
        return handle(text, fromUserId, false);
    }

    /**
     * 处理纯文本消息，可指定是否为语音回复模式
     */
    public String handle(String text, String fromUserId, boolean voiceReply) {
        if (text.startsWith("/")) {
            String result = commandManager.dispatch(text);
            if (result != null) {
                return result;
            }
            return "未知命令，输入 /help 查看可用命令";
        }

        log.info("非命令消息，交给 LLM 处理: from={}, text={}, voiceReply={}", fromUserId, text, voiceReply);
        sessionManager.addMessage(fromUserId, "user", text);
        String reply = llmService.chat(sessionManager.getHistory(fromUserId), text, voiceReply);
        sessionManager.addMessage(fromUserId, "assistant", reply);
        return reply;
    }

    /**
     * 处理带图片的消息（多模态）
     * <ul>
     *   <li>以 {@code /} 开头的文本 → 交给 CommandManager 分发（不入历史，忽略图片）</li>
     *   <li>其他文本 → 会话记忆中用占位文本标记图片，实际图片传给 LLM 多模态接口</li>
     * </ul>
     */
    public String handle(String text, String fromUserId, byte[] imageBytes, String imageMimeType) {
        if (text.startsWith("/")) {
            String result = commandManager.dispatch(text);
            if (result != null) {
                return result;
            }
            return "未知命令，输入 /help 查看可用命令";
        }

        log.info("非命令图片消息，交给 LLM 处理: from={}, text={}, mime={}",
                fromUserId, text, imageMimeType);

        sessionManager.addMessage(fromUserId, "user", IMAGE_PLACEHOLDER);
        String reply = llmService.chatWithImage(
                sessionManager.getHistory(fromUserId), text, imageBytes, imageMimeType);
        sessionManager.addMessage(fromUserId, "assistant", reply);
        return reply;
    }
}
