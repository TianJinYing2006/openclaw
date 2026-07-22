package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.ConversationMessage;

import java.util.List;

/**
 * 模型网关的统一抽象。所有 AI 请求（纯文本、多模态、Tool 调用）都通过此接口。
 *
 * <p>后续扩展新供应商只需在网关内部切换，对外部调用方透明。</p>
 */
public interface LlmGateway {

    /**
     * @param history 当前用户此前成功的一问一答，由 Java 内存保存
     * @param prompt 当前这一轮的问题
     * @param images 当前这一轮的图片，不进入长期历史
     * @param files 当前这一轮的文件，不进入长期历史
     */
    ModelReply generate(List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files);

    /** 特殊任务可覆盖本次 reasoning effort。 */
    default ModelReply generate(List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files, String reasoningEffort) {
        return generate(history, prompt, images, files);
    }

    /**
     * 支持 model 覆盖。传 null 或空时使用网关默认模型。
     * 用于 Tool 内部切换同协议下的不同模型（如 gpt-4o-mini 做简单总结，o3 做深度推理）。
     */
    default ModelReply generate(List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files,
                                String reasoningEffort, String model) {
        return generate(history, prompt, images, files, reasoningEffort);
    }

    /** text 是最终回答；model 是第三方响应报告的实际模型名，当前主要用于日志核验。 */
    record ModelReply(String text, String model) {
    }
}
