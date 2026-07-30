package com.example.ykdsummer.bot.runtime;

import com.example.ykdsummer.reminder.persistence.ILinkReplyContextPersistence;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 保存近期微信会话的回信上下文，供后台任务主动发送结果。 */
@Component
public class ILinkReplyContextStore {
    private static final Logger log = LoggerFactory.getLogger(ILinkReplyContextStore.class);
    private final Cache<String, String> contexts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();
    private final ObjectProvider<ILinkReplyContextPersistence> persistentContexts;

    /** Supports focused unit tests where MySQL persistence is intentionally absent. */
    public ILinkReplyContextStore() {
        this.persistentContexts = null;
    }

    @Autowired
    public ILinkReplyContextStore(ObjectProvider<ILinkReplyContextPersistence> persistentContexts) {
        this.persistentContexts = persistentContexts;
    }

    public void remember(String userId, String contextToken) {
        String user = safe(userId);
        String context = safe(contextToken);
        if (!user.isBlank() && !context.isBlank()) {
            contexts.put(user, context);
            ILinkReplyContextPersistence persistence = persistence();
            if (persistence != null) {
                try {
                    persistence.save(user, context);
                } catch (RuntimeException exception) {
                    // An inbound iLink reply must still work when local persistence is temporarily unavailable.
                    log.warn("Could not persist iLink reply context for user={}", anonymize(user), exception);
                }
            }
        }
    }

    public Optional<String> find(String userId) {
        String user = safe(userId);
        String cached = contexts.getIfPresent(user);
        if (cached != null) return Optional.of(cached);
        ILinkReplyContextPersistence persistence = persistence();
        if (persistence == null || user.isBlank()) return Optional.empty();
        try {
            Optional<String> restored = persistence.find(user);
            restored.ifPresent(value -> contexts.put(user, value));
            return restored;
        } catch (RuntimeException exception) {
            log.warn("Could not restore iLink reply context for user={}", anonymize(user), exception);
            return Optional.empty();
        }
    }

    private ILinkReplyContextPersistence persistence() {
        return persistentContexts == null ? null : persistentContexts.getIfAvailable();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
