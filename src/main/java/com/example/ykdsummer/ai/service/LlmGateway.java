package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.ConversationMessage;

import java.util.List;

/**
 * AiChatService 与具体模型协议之间的一层小接口。正式运行使用 RoutingLlmGateway：
 * 普通纯文本进入 Spring AI Chat Completions，文件和多模态进入 Responses。
 * 单元测试可传入假的实现并检查历史、当前问题和媒体，而不消耗真实额度。
 */
public interface LlmGateway {

    /**
     * @param history 当前用户此前成功的一问一答，由 Java 内存保存
     * @param prompt 当前这一轮的问题
     * @param images 当前这一轮的图片，不进入长期历史
     * @param files 当前这一轮的文件，不进入长期历史
     */
    ModelReply generate(List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files);

    /** 带调用者身份的重载，纯文本 Agent 工具可据此隔离本地图片版本。 */
    default ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files) {
        return generate(history, prompt, images, files);
    }

    /**
     * 带本轮预算的调用。旧实现和测试 fake gateway 可继续只实现四参数方法；正式路由器会把
     * 预算传给对应协议网关，分别映射为 Chat Completions 的 max_completion_tokens 与
     * Responses 的 max_output_tokens。
     */
    default ModelReply generate(String userId, List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files, AiRequestBudget budget) {
        return generate(userId, history, prompt, images, files);
    }

    /** 特殊任务可覆盖本次 reasoning effort；测试假实现默认仍复用四参数方法。 */
    default ModelReply generate(List<ConversationMessage> history, String prompt,
                                List<AiImage> images, List<AiFile> files, String reasoningEffort) {
        return generate(history, prompt, images, files);
    }

    /** text 是最终回答；model 是第三方响应报告的实际模型名；usage 仅记录标准响应字段。 */
    record ModelReply(String text, String model, List<AiArtifact> artifacts, AiModelUsage usage, String protocol) {
        public ModelReply(String text, String model) {
            this(text, model, List.of(), AiModelUsage.unknown(), "unknown");
        }

        public ModelReply(String text, String model, List<AiArtifact> artifacts) {
            this(text, model, artifacts, AiModelUsage.unknown(), "unknown");
        }

        public ModelReply {
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            usage = usage == null ? AiModelUsage.unknown() : usage;
            protocol = protocol == null || protocol.isBlank() ? "unknown" : protocol.strip();
        }
    }
}
