package com.wechatbot.fashion.bot.service;

import com.wechatbot.fashion.admin.ilink.ManagedBotInstanceManager;
import com.wechatbot.fashion.bot.runtime.ILinkReplyContextStore;
import com.wechatbot.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.wechatbot.fashion.wardrobe.runtime.FashionWardrobePhotoAnalyzedEvent;
import com.wechatbot.fashion.wardrobe.runtime.FashionWardrobePhotoAnalysisFailedEvent;
import com.wechatbot.fashion.persistence.ManagedInstanceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Pushes the result of a background wardrobe photo analysis back to the user's WeChat chat. */
@Component
public class ILinkFashionWardrobeAnalysisCompletionListener {
    private static final Logger log = LoggerFactory.getLogger(ILinkFashionWardrobeAnalysisCompletionListener.class);
    private final ILinkBotService directBot;
    private final ILinkReplyContextStore contexts;
    private final ObjectProvider<ManagedBotInstanceManager> managedBots;
    private final ObjectProvider<FashionWardrobeIngestionService> wardrobeIngestion;

    /** Supports focused unit tests when managed bot routing is intentionally absent. */
    public ILinkFashionWardrobeAnalysisCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts) {
        this(directBot, contexts, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkFashionWardrobeAnalysisCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts,
                                                          ObjectProvider<ManagedBotInstanceManager> managedBots,
                                                          ObjectProvider<FashionWardrobeIngestionService> wardrobeIngestion) {
        this.directBot = directBot;
        this.contexts = contexts;
        this.managedBots = managedBots;
        this.wardrobeIngestion = wardrobeIngestion;
    }

    @EventListener
    public void pushPhotoAnalysisResult(FashionWardrobePhotoAnalyzedEvent event) {
        if (event == null || event.userId().isBlank()) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendText(event.userId(), contextToken, analyzedMessage(event));
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send wardrobe photo analysis, image={}, user={}",
                        event.imageAssetId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No active iLink reply context for wardrobe photo analysis, image={}, user={}",
                event.imageAssetId(), anonymize(event.userId())));
    }

    @EventListener
    public void pushPhotoAnalysisFailure(FashionWardrobePhotoAnalysisFailedEvent event) {
        if (event == null || event.userId().isBlank()) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendText(event.userId(), contextToken, failedMessage(event));
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send wardrobe photo analysis failure, image={}, user={}",
                        event.imageAssetId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No active iLink reply context for wardrobe photo analysis failure, image={}, user={}",
                event.imageAssetId(), anonymize(event.userId())));
    }

    private String analyzedMessage(FashionWardrobePhotoAnalyzedEvent event) {
        StringBuilder message = new StringBuilder();
        if (!event.summary().isBlank()) message.append(event.summary()).append('\n');
        if (event.candidateNames().isEmpty()) {
            message.append("没有识别到可可靠提取的单品。请补拍目标衣物的大部分轮廓，确保类别、颜色和主要形状清晰可见。");
            return message.toString();
        }
        message.append("识别完成，找到 ").append(event.candidateNames().size()).append(" 件可入库单品：\n");
        event.candidateNames().forEach(name -> message.append("- ").append(name).append('\n'));
        message.append("回复“确认”即可加入衣橱；想修改名称或标签，或想先看候选详情，直接告诉我。");
        return message.toString();
    }

    private static String failedMessage(FashionWardrobePhotoAnalysisFailedEvent event) {
        return "这次图片识别没有成功，请稍后重试或重新拍摄一张更清晰的照片。";
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
