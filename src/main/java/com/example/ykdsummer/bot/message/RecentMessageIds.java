package com.example.ykdsummer.bot.message;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最近消息 ID 的内存去重窗口，用来避免同一条消息被重复回复。
 *
 * <p>长轮询出现重试、程序在游标提交前异常等情况时，同一个 messageId 可能再次到达。
 * 这里使用带容量上限的 {@link LinkedHashMap} 保存近期 ID，防止内存无限增长。</p>
 *
 * <p>它只存在于当前 Java 进程，重启后会清空。单机 Demo 足够；多实例生产系统应把
 * messageId 放进 Redis 或数据库，并设置过期时间。</p>
 */
public final class RecentMessageIds {

    private final Map<Long, Boolean> ids;

    /**
     * @param maximumSize 最多保留多少个近期消息 ID，必须大于 0
     */
    public RecentMessageIds(int maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        // accessOrder=true：访问过的 ID 会移动到队尾，最久未使用的 ID 最先被淘汰。
        ids = new LinkedHashMap<>(maximumSize + 1, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > maximumSize;
            }
        };
    }

    /** 查询消息是否处理过；synchronized 防止 SDK 回调线程并发修改 Map。 */
    public synchronized boolean contains(Long messageId) {
        return messageId != null && ids.containsKey(messageId);
    }

    /** 在业务处理完成或确认不需要处理后记住消息 ID。 */
    public synchronized void remember(Long messageId) {
        if (messageId != null) {
            ids.put(messageId, Boolean.TRUE);
        }
    }
}
