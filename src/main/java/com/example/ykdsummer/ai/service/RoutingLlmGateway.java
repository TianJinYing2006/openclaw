package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在 Chat Completions 与 Responses 之间做唯一、集中、可测试的协议选择。
 *
 * <ul>
 *   <li>普通纯文本：Spring AI Chat Completions。</li>
 *   <li>带图片或文件：OpenAI Responses。</li>
 *   <li>显式 reasoning effort：OpenAI Responses。</li>
 * </ul>
 */
@Service
@Primary
public class RoutingLlmGateway implements LlmGateway {

    private final TextChatGateway textChatGateway;
    private final ResponsesGateway responsesGateway;
    private final AiTraceLogger trace;

    /** 测试用构造器；生产环境使用 {@link Autowired} 三参数构造器。 */
    public RoutingLlmGateway(TextChatGateway textChatGateway, ResponsesGateway responsesGateway) {
        this(textChatGateway, responsesGateway, AiTraceLogger.disabled());
    }

    @Autowired
    public RoutingLlmGateway(
            TextChatGateway textChatGateway,
            ResponsesGateway responsesGateway,
            AiTraceLogger trace
    ) {
        this.textChatGateway = textChatGateway;
        this.responsesGateway = responsesGateway;
        this.trace = trace;
    }

    @Override
    public ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files) {
        return route(userId, history, prompt, images, files, null, null);
    }

    @Override
    public ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files, AiRequestBudget budget) {
        return route(userId, history, prompt, images, files, budget, null);
    }

    @Override
    public ModelReply generate(List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files) {
        return route(null, history, prompt, images, files, null, null);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files,
            String reasoningEffort
    ) {
        return route(null, history, prompt, images, files, null, reasoningEffort);
    }

    /**
     * 唯一的路由裁决点。所有 {@code generate} 重载最终都汇聚到此方法。
     */
    private ModelReply route(String userId, List<ConversationMessage> history, String prompt,
                             List<AiImage> images, List<AiFile> files,
                             AiRequestBudget budget, String reasoningEffort) {
        // reasoning effort 显式指定 → 必须走 Responses
        if (reasoningEffort != null) {
            trace.route("Responses", "显式 reasoning effort=" + reasoningEffort,
                    size(images), size(files));
            return responsesGateway.generate(history, prompt, images, files, reasoningEffort);
        }
        // 仅含文字 → Chat Completions
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions", "纯文本", 0, 0);
            return budget != null
                    ? textChatGateway.generate(userId, history, prompt, budget)
                    : textChatGateway.generate(userId, history, prompt);
        }
        // 含图片或文件 → Responses
        trace.route("Responses", "多模态", size(images), size(files));
        return budget != null
                ? responsesGateway.generate(userId, history, prompt, images, files, budget)
                : responsesGateway.generate(history, prompt, images, files);
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
