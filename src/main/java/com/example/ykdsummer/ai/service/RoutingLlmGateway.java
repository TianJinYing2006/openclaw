package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
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

    private static final Logger log = LoggerFactory.getLogger(RoutingLlmGateway.class);

    private final TextChatGateway textChatGateway;
    private final ResponsesGateway responsesGateway;

    public RoutingLlmGateway(TextChatGateway textChatGateway, ResponsesGateway responsesGateway) {
        this.textChatGateway = textChatGateway;
        this.responsesGateway = responsesGateway;
    }

    @Override
    public ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (isEmpty(images) && isEmpty(files)) {
            log.info("大模型路由：协议=Chat Completions，媒体=无");
            return textChatGateway.generate(history, prompt);
        }
        log.info("大模型路由：协议=Responses，图片数量={}，文件数量={}", sizeOf(images), sizeOf(files));
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
        log.info(
                "大模型路由：协议=Responses，强制路由=true，图片数量={}，文件数量={}，推理强度={}",
                sizeOf(images),
                sizeOf(files),
                reasoningEffort
        );
        return responsesGateway.generate(history, prompt, images, files, reasoningEffort);
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
