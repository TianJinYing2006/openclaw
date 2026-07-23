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
 *   <li>普通纯文本四参数调用：Spring AI Chat Completions。</li>
 *   <li>带图片或文件：OpenAI Responses。</li>
 *   <li>显式 reasoning effort 的五参数任务：OpenAI Responses。</li>
 * </ul>
 */
@Service
@Primary
public class RoutingLlmGateway implements LlmGateway {

    private final TextChatGateway textChatGateway;
    private final ResponsesGateway responsesGateway;
    private final AiTraceLogger trace;

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
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(userId, history, prompt);
        }
        trace.route("Responses (/v1/responses)", "本轮包含图片或未在本地转成文字的文件", size(images), size(files));
        return responsesGateway.generate(history, prompt, images, files);
    }

    @Override
    public ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                               List<AiImage> images, List<AiFile> files, AiRequestBudget budget) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(userId, history, prompt, budget);
        }
        trace.route("Responses (/v1/responses)", "本轮包含图片或未在本地转成文字的文件", size(images), size(files));
        return responsesGateway.generate(userId, history, prompt, images, files, budget);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (isEmpty(images) && isEmpty(files)) {
            trace.route("Chat Completions (/v1/chat/completions)", "本轮只有文字", 0, 0);
            return textChatGateway.generate(history, prompt);
        }
        trace.route(
                "Responses (/v1/responses)",
                "本轮包含图片或未在本地转成文字的文件",
                size(images),
                size(files)
        );
        return responsesGateway.generate(history, prompt, images, files);
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files,
            String reasoningEffort
    ) {
        trace.route(
                "Responses (/v1/responses)",
                "业务显式指定 reasoning effort=" + reasoningEffort,
                size(images),
                size(files)
        );
        return responsesGateway.generate(history, prompt, images, files, reasoningEffort);
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
