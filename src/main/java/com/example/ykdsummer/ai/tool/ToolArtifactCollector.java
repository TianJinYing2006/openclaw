package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 连接 Spring AI 工具和微信回复层的“一轮请求收件箱”。
 * 同一条模型调用在同一个执行线程中完成；结束时立即清空，避免不同微信用户串图。
 */
@Component
public class ToolArtifactCollector {
    private final ThreadLocal<Context> current = new ThreadLocal<>();

    public void begin(String userId) {
        current.set(new Context(userId, new ArrayList<>()));
    }

    public String userId() {
        Context context = current.get();
        return context == null ? "unknown" : context.userId();
    }

    public void add(AiArtifact artifact) {
        Context context = current.get();
        if (context != null && artifact != null) {
            context.artifacts().add(artifact);
        }
    }

    public List<AiArtifact> finish() {
        Context context = current.get();
        current.remove();
        return context == null ? List.of() : List.copyOf(context.artifacts());
    }

    public void discard() {
        current.remove();
    }

    private record Context(String userId, List<AiArtifact> artifacts) { }
}
