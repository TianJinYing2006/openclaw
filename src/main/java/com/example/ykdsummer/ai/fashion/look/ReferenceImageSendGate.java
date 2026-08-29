package com.example.ykdsummer.ai.fashion.look;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 参考穿搭图片发送门控。
 *
 * <p>让图片发送与主模型文本生成并行：fashion_consultant 提交图片发送后立即返回
 * （不阻塞文本生成），但把"这批图片全部发送完成"的信号登记在案；主流程在最终
 * 发送文本前调用 {@link #await} 等待该信号，从而固定"整套图 → 参考拼图 → 文本描述"
 * 的推送顺序，同时几乎不增加文本到达耗时。</p>
 */
@Component
public class ReferenceImageSendGate {

    private static final Logger log = LoggerFactory.getLogger(ReferenceImageSendGate.class);

    /** userId → 该用户最近一批参考图发送的聚合完成信号；await 后移除。 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** 登记一批参考图发送的完成信号（覆盖同用户上一批残留）。 */
    public void track(String userId, CompletableFuture<Void> allDone) {
        if (userId == null || allDone == null) {
            return;
        }
        pending.put(userId, allDone);
    }

    /** 等待该用户最近一批参考图发送完成；无登记或已完成立即返回。 */
    public void await(String userId, long timeoutMillis) {
        if (userId == null) {
            return;
        }
        CompletableFuture<Void> future = pending.remove(userId);
        if (future == null) {
            return;
        }
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Reference images not flushed within {}ms for user {}: {}",
                    timeoutMillis, Integer.toHexString(userId.hashCode()), e.getMessage());
        }
    }
}
