package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.fashion.runtime.FashionTryOnCompletedEvent;
import com.example.ykdsummer.fashion.runtime.FashionTryOnFailedEvent;
import com.example.ykdsummer.persistence.ManagedInstanceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Delivers a durable completed try-on preview to the same scoped WeChat user. */
@Component
public class ILinkFashionTryOnCompletionListener {
    private static final Logger log = LoggerFactory.getLogger(ILinkFashionTryOnCompletionListener.class);
    private final ILinkBotService directBot;
    private final ILinkReplyContextStore contexts;
    private final ObjectProvider<ManagedBotInstanceManager> managedBots;

    public ILinkFashionTryOnCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts) {
        this(directBot, contexts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkFashionTryOnCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts,
                                                ObjectProvider<ManagedBotInstanceManager> managedBots) {
        this.directBot = directBot;
        this.contexts = contexts;
        this.managedBots = managedBots;
    }

    @EventListener
    public void pushCompletedTryOn(FashionTryOnCompletedEvent event) {
        if (event == null || event.imageBytes().length == 0) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendImage(event.userId(), contextToken, event.imageBytes());
                sendText(event.userId(), contextToken,
                        "上身效果图已生成。这是 AI 试衣预览；满意的话可以继续换另一件衣服试试。" );
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send fashion try-on, task={}, user={}", event.taskId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No iLink reply context for fashion try-on task {}, user={}", event.taskId(), anonymize(event.userId())));
    }

    @EventListener
    public void notifyTryOnFailure(FashionTryOnFailedEvent event) {
        if (event == null || event.userId().isBlank()) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendText(event.userId(), contextToken,
                        "抱歉，刚才的试衣生成失败了（" + userFacingReason(event.reason()) + "）。请稍后再试一次。");
            } catch (RuntimeException exception) {
                log.warn("Could not notify fashion try-on failure, task={}, user={}", event.taskId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No iLink reply context for fashion try-on failure, task={}, user={}", event.taskId(), anonymize(event.userId())));
    }

    /** 把技术性失败原因转成用户能看懂的一句话；空原因给兜底文案。 */
    private static String userFacingReason(String reason) {
        if (reason == null || reason.isBlank()) return "服务暂时不可用";
        String message = reason.strip().replaceAll("\\s+", " ");
        if (message.length() > 60) message = message.substring(0, 60) + "…";
        return message;
    }

    private void sendImage(String userId, String contextToken, byte[] bytes) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(userId);
        if (scope.managed()) {
            ManagedBotInstanceManager manager = managedBots == null ? null : managedBots.getIfAvailable();
            if (manager == null || !manager.sendImage(userId, contextToken, bytes)) {
                throw new IllegalStateException("Managed iLink instance is not connected");
            }
            return;
        }
        directBot.sendImage(userId, contextToken, bytes);
    }

    private void sendText(String userId, String contextToken, String text) {
        ManagedInstanceScope scope = ManagedInstanceScope.parse(userId);
        if (scope.managed()) {
            ManagedBotInstanceManager manager = managedBots == null ? null : managedBots.getIfAvailable();
            if (manager == null || !manager.sendText(userId, contextToken, text)) {
                throw new IllegalStateException("Managed iLink instance is not connected");
            }
            return;
        }
        directBot.sendText(userId, contextToken, text);
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
