package com.wechatbot.fashion.ai.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.content.Content;
import org.springframework.ai.observation.ObservabilityHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 把 Spring AI 的 ChatModel 观测上下文中的 prompt / completion 内容，
 * 作为 {@code gen_ai.prompt} / {@code gen_ai.completion} 高基数属性挂到 OTel span 上。
 *
 * <p>不注册本 Filter 时，Langfuse 里的 generation 只会显示模型名与 token 数，
 * input/output 字段为 null；注册后才能在 Langfuse 看到完整的请求与回复内容。
 * 来自 Langfuse 官方 Spring AI 集成文档。
 */
@Component
public class ChatModelCompletionContentObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext chatModelObservationContext)) {
            return context;
        }

        var prompts = processPrompts(chatModelObservationContext);
        var completions = processCompletion(chatModelObservationContext);

        chatModelObservationContext.addHighCardinalityKeyValue(new KeyValue() {
            @Override
            public String getKey() {
                return "gen_ai.prompt";
            }

            @Override
            public String getValue() {
                return ObservabilityHelper.concatenateStrings(prompts);
            }
        });

        chatModelObservationContext.addHighCardinalityKeyValue(new KeyValue() {
            @Override
            public String getKey() {
                return "gen_ai.completion";
            }

            @Override
            public String getValue() {
                return ObservabilityHelper.concatenateStrings(completions);
            }
        });

        addUsageKeyValue(chatModelObservationContext, "gen_ai.usage.input_tokens",
                chatModelObservationContext.getResponse() == null
                        || chatModelObservationContext.getResponse().getMetadata() == null
                        || chatModelObservationContext.getResponse().getMetadata().getUsage() == null
                        ? null
                        : chatModelObservationContext.getResponse().getMetadata().getUsage().getPromptTokens());
        addUsageKeyValue(chatModelObservationContext, "gen_ai.usage.output_tokens",
                chatModelObservationContext.getResponse() == null
                        || chatModelObservationContext.getResponse().getMetadata() == null
                        || chatModelObservationContext.getResponse().getMetadata().getUsage() == null
                        ? null
                        : chatModelObservationContext.getResponse().getMetadata().getUsage().getCompletionTokens());

        return chatModelObservationContext;
    }

    /**
     * 把 token 用量挂成 {@code gen_ai.usage.*} 属性（Langfuse OTel 映射的标准键）。
     * Spring AI 1.1.8 的 ChatModel 观测默认不带 usage 属性——实测 Langfuse generation 的
     * inputTokens/outputTokens 为空；本方法补齐，Langfuse 才能拿到 token 成本。
     */
    private static void addUsageKeyValue(ChatModelObservationContext ctx, String key, Integer value) {
        if (value == null || value < 0) {
            return;
        }
        ctx.addHighCardinalityKeyValue(new KeyValue() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public String getValue() {
                return String.valueOf(value);
            }
        });
    }

    private List<String> processPrompts(ChatModelObservationContext chatModelObservationContext) {
        return CollectionUtils.isEmpty((chatModelObservationContext.getRequest()).getInstructions())
                ? List.of()
                : (chatModelObservationContext.getRequest()).getInstructions().stream()
                    .map(Content::getText).toList();
    }

    private List<String> processCompletion(ChatModelObservationContext context) {
        if (context.getResponse() != null && (context.getResponse()).getResults() != null
                && !CollectionUtils.isEmpty((context.getResponse()).getResults())) {
            return !StringUtils.hasText((context.getResponse()).getResult().getOutput().getText())
                    ? List.of()
                    : (context.getResponse()).getResults().stream()
                        .filter((generation) -> generation.getOutput() != null
                                && StringUtils.hasText(generation.getOutput().getText()))
                        .map((generation) -> generation.getOutput().getText()).toList();
        } else {
            return List.of();
        }
    }
}
