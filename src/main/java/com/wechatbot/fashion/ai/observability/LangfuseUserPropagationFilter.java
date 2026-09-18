package com.wechatbot.fashion.ai.observability;

import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.stereotype.Component;

/**
 * 把当前请求的微信用户标识（{@link AgentSessionContext} 中的 userId）作为
 * {@code langfuse.user.id} 高基数属性注入到每一个 Observation（即每条 OTel span）。
 *
 * <p>为什么需要它：Langfuse 官方 OTel 文档明确要求 trace 级属性（如 langfuse.user.id）
 * 必须出现在 trace 内的<b>每一个</b> span 上，而不能只打在根 span。
 * 之前只在 {@code AgentCoordinator} 的根 span 上设了 langfuse.user.id，
 * 而真正携带 token 的 GENERATION 子 span（由 Spring AI ChatModel 产生）没有该属性，
 * 导致 Langfuse 里 userId 为空、用量报表「按用户排名」只能显示「未知用户」。</p>
 *
 * <p>{@link AgentSessionContext} 在每次对话请求进入 gateway 前由
 * {@code AiChatService} 通过 ThreadLocal 设置，整条工具链（含子 Agent 的 LLM 调用）都能读到，
 * 因此这里注入的一定是真实微信用户标识（形如 {@code managed:<instanceId>:<wxid>} 或原始 wxid）。</p>
 *
 * <p>机制与 {@link ChatModelCompletionContentObservationFilter} 一致：用
 * {@code addHighCardinalityKeyValue} 注入的属性会随 Observation 导出为 OTel span 属性，
 * 从而被 Langfuse 识别为 trace 级 userId。</p>
 */
@Component
public class LangfuseUserPropagationFilter implements ObservationFilter {

    /** 当会话上下文未初始化（或宽松语义下为 anonymous）时不注入，避免把真实数据串到匿名身份。 */
    @Override
    public Observation.Context map(Observation.Context context) {
        String userId = AgentSessionContext.currentUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return context;
        }
        context.addHighCardinalityKeyValue(KeyValue.of("langfuse.user.id", userId));
        return context;
    }
}
