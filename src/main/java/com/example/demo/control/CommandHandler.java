package com.example.demo.control;

import com.example.demo.service.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 微信消息命令处理器 — 将收到的文本消息交给 CommandManager 统一分发
 * 非命令消息转发给 LLM 处理
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Autowired
    @Lazy
    private LLMService llmService;

    /**
     * 处理文本消息，优先匹配命令，否则交给 LLM
     */
    public String handle(String text, String fromUserId) {
        // 先尝试匹配已注册命令
        String result = commandManager.dispatch(text);
        if (result != null) {
            return result;
        }
        // 非命令消息转发给 LLM
        log.info("非命令消息，交给 LLM 处理: from={}, text={}", fromUserId, text);
        return llmService.chat(text);
    }
}
