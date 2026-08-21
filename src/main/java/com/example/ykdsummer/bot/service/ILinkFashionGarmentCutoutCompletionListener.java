package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import com.example.ykdsummer.fashion.wardrobe.application.FashionWardrobeIngestionService;
import com.example.ykdsummer.fashion.wardrobe.runtime.FashionGarmentCutoutCompletedEvent;
import com.example.ykdsummer.fashion.wardrobe.runtime.FashionGarmentCutoutFailedEvent;
import com.example.ykdsummer.persistence.ManagedInstanceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Pushes a completed garment cutout back to its owner and asks for the final wardrobe confirmation. */
@Component
public class ILinkFashionGarmentCutoutCompletionListener {
    private static final Logger log = LoggerFactory.getLogger(ILinkFashionGarmentCutoutCompletionListener.class);
    private final ILinkBotService directBot;
    private final ILinkReplyContextStore contexts;
    private final ObjectProvider<ManagedBotInstanceManager> managedBots;
    private final ObjectProvider<FashionWardrobeIngestionService> wardrobeIngestion;

    /** Supports focused unit tests when managed bot routing is intentionally absent. */
    public ILinkFashionGarmentCutoutCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts) {
        this(directBot, contexts, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkFashionGarmentCutoutCompletionListener(ILinkBotService directBot, ILinkReplyContextStore contexts,
                                                        ObjectProvider<ManagedBotInstanceManager> managedBots,
                                                        ObjectProvider<FashionWardrobeIngestionService> wardrobeIngestion) {
        this.directBot = directBot;
        this.contexts = contexts;
        this.managedBots = managedBots;
        this.wardrobeIngestion = wardrobeIngestion;
    }

    @EventListener
    public void pushCompletedCutout(FashionGarmentCutoutCompletedEvent event) {
        if (event == null || event.imageBytes().length == 0) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendImage(event.userId(), contextToken, event.imageBytes());
                sendText(event.userId(), contextToken, completionMessage(event));
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send garment cutout, task={}, user={}", event.taskId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No active iLink reply context for garment cutout task {}, user={}",
                event.taskId(), anonymize(event.userId())));
    }

    @EventListener
    public void pushFailedCutout(FashionGarmentCutoutFailedEvent event) {
        if (event == null || event.userId().isBlank()) return;
        contexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                sendText(event.userId(), contextToken, failureMessage(event));
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send garment cutout failure, task={}, user={}",
                        event.taskId(), anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No active iLink reply context for failed garment cutout task {}, user={}",
                event.taskId(), anonymize(event.userId())));
    }

    private String completionMessage(FashionGarmentCutoutCompletedEvent event) {
        FashionWardrobeIngestionService service = wardrobeIngestion == null ? null : wardrobeIngestion.getIfAvailable();
        String review = service == null ? "" : service.candidate(event.userId(), event.candidateId())
                .map(service::draftReviewSummary)
                .orElse("");
        StringBuilder message = new StringBuilder("衣物草稿已生成，请查看图片。\n");
        if (!review.isBlank()) message.append(review).append('\n');
        message.append("满意就回复“确认加入衣橱”；需要改标签可直接说“颜色改深灰”或“改成夏季”。\n")
                .append("想改图可直接说“把这一版衣长加长一点”，新图会作为另一版保留，可随时说“看第一版/第二版”或“确认第二版加入衣橱”。")
                .append("草稿 30 分钟未确认会自动清理。\n")
                .append("提示：修改标签只修改衣橱资料；重新裁图只调整边缘和展示效果；改图才会调整衣服本身的视觉效果。");
        return message.toString();
    }

    private static String failureMessage(FashionGarmentCutoutFailedEvent event) {
        String reason = event.failureSummary().contains("超时") || event.failureSummary().contains("没有响应")
                ? "图片服务在等待时限内没有返回结果"
                : "图片服务本次没有生成可用结果";
        return "这次衣物草稿没有生成成功（" + reason + "）。没有加入衣橱，也不会覆盖已有草稿。"
                + "你可以稍后直接说“重新抠图”再试。";
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
