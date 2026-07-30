package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.fashion.domain.OutfitRenderStatus;
import com.example.ykdsummer.fashion.runtime.FashionOutfitRecommendationCompletedEvent;
import com.example.ykdsummer.persistence.ManagedInstanceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Sends each completed ranked outfit board to the same WeChat user without blocking the original message. */
@Component
public class ILinkFashionOutfitRecommendationCompletionListener {
    private static final Logger log =
            LoggerFactory.getLogger(ILinkFashionOutfitRecommendationCompletionListener.class);
    private final ILinkBotService directBot;
    private final ILinkReplyContextStore contexts;
    private final ObjectProvider<ManagedBotInstanceManager> managedBots;

    public ILinkFashionOutfitRecommendationCompletionListener(
            ILinkBotService directBot,
            ILinkReplyContextStore contexts
    ) {
        this(directBot, contexts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkFashionOutfitRecommendationCompletionListener(
            ILinkBotService directBot,
            ILinkReplyContextStore contexts,
            ObjectProvider<ManagedBotInstanceManager> managedBots
    ) {
        this.directBot = directBot;
        this.contexts = contexts;
        this.managedBots = managedBots;
    }

    @EventListener
    public void pushCompletedOutfit(FashionOutfitRecommendationCompletedEvent event) {
        if (event == null || event.imageBytes().length == 0) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendImage(event.userId(), contextToken, event.imageBytes());
                String suffix = event.renderStatus() == OutfitRenderStatus.FALLBACK
                        ? "这张使用真实衣橱单品拼接，服装细节以原图为准。"
                        : "这是 AI 搭配展示图，服装细节仍以衣橱原图为准。";
                sendText(event.userId(), contextToken,
                        "第 " + event.rank() + " 套：" + event.displaySummary() + "。" + suffix);
            } catch (RuntimeException failure) {
                log.warn("Could not auto-send outfit recommendation option={}, user={}",
                        event.optionId(), anonymize(event.userId()), failure);
            }
        }, () -> log.warn("No iLink reply context for outfit recommendation option={}, user={}",
                event.optionId(), anonymize(event.userId())));
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
