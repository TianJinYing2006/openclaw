package com.example.ykdsummer.bot.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 保存近期微信会话的回信上下文，供后台任务主动发送结果。 */
@Component
public class ILinkReplyContextStore {
    private final Cache<String, String> contexts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    public void remember(String userId, String contextToken) {
        String user = safe(userId);
        String context = safe(contextToken);
        if (!user.isBlank() && !context.isBlank()) {
            contexts.put(user, context);
        }
    }

    public Optional<String> find(String userId) {
        return Optional.ofNullable(contexts.getIfPresent(safe(userId)));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u0000', ' ').strip();
    }
}
