package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.ConversationMessage;

import java.util.List;

/**
 * AiChatService 与具体 HTTP SDK 之间的一层小接口。正式运行使用 OpenAiResponsesGateway，
 * 单元测试可传入假的实现并检查历史、当前问题和图片，而不消耗真实额度。
 */
public interface LlmGateway {

    /**
     * @param history 当前用户此前成功的一问一答，由 Java 内存保存
     * @param prompt 当前这一轮的问题
     * @param images 当前这一轮的图片，不进入长期历史
     * @param files 当前这一轮的文件，不进入长期历史
     */
    ModelReply generate(List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files);

    /** 特殊任务可覆盖本次 reasoning effort；测试假实现默认仍复用四参数方法。 */
    default ModelReply generate(List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files, String reasoningEffort) {
        return generate(history, prompt, images, files);
    }

    /** text 是最终回答；model 是第三方响应报告的实际模型名，当前主要用于日志核验。 */
    record ModelReply(String text, String model) {
    }
}
