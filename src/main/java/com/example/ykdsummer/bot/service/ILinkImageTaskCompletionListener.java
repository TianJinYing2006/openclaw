package com.example.ykdsummer.bot.service;

import com.example.ykdsummer.ai.tool.ImageTaskCompletionEvent;
import com.example.ykdsummer.admin.ilink.ManagedBotInstanceManager;
import com.example.ykdsummer.bot.runtime.ILinkReplyContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 将已保存的后台图片任务自动发送给对应微信用户。 */
@Component
public class ILinkImageTaskCompletionListener {
    private static final Logger log = LoggerFactory.getLogger(ILinkImageTaskCompletionListener.class);

    private final ILinkBotService botService;
    private final ILinkReplyContextStore replyContexts;
    private final ObjectProvider<ManagedBotInstanceManager> managedInstances;

    public ILinkImageTaskCompletionListener(ILinkBotService botService, ILinkReplyContextStore replyContexts) {
        this(botService, replyContexts, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ILinkImageTaskCompletionListener(ILinkBotService botService, ILinkReplyContextStore replyContexts,
                                            ObjectProvider<ManagedBotInstanceManager> managedInstances) {
        this.botService = botService;
        this.replyContexts = replyContexts;
        this.managedInstances = managedInstances;
    }

    @EventListener
    public void sendCompletedImage(ImageTaskCompletionEvent event) {
        if (event == null || event.imageBytes().length == 0) {
            return;
        }
        ManagedBotInstanceManager manager = managedInstances == null ? null : managedInstances.getIfAvailable();
        if (manager != null && manager.sendCompletedImage(event)) {
            return;
        }
        replyContexts.find(event.userId()).ifPresentOrElse(contextToken -> {
            try {
                botService.sendGeneratedImage(event.userId(), contextToken, event.taskId(), event.imageBytes());
            } catch (RuntimeException exception) {
                log.warn("Could not auto-send completed image task {}, user={}", event.taskId(),
                        anonymize(event.userId()), exception);
            }
        }, () -> log.warn("No active iLink reply context for completed image task {}, user={}", event.taskId(),
                anonymize(event.userId())));
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
