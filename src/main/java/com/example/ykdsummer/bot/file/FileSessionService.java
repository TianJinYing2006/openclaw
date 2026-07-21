package com.example.ykdsummer.bot.file;

import com.example.ykdsummer.ai.model.AiFile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/** 复刻 TJY 的一次性文件会话：文件等待下一条自然语言指令，5 分钟后自动过期。 */
@Service
public class FileSessionService {

    private final Cache<String, AiFile> files = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public void cache(String userId, AiFile file) {
        files.put(userId, file);
    }

    public Optional<AiFile> consume(String userId) {
        AiFile file = files.getIfPresent(userId);
        if (file != null) {
            files.invalidate(userId);
        }
        return Optional.ofNullable(file);
    }

    public void clear(String userId) {
        files.invalidate(userId);
    }

    int size() {
        files.cleanUp();
        return Math.toIntExact(files.estimatedSize());
    }
}
