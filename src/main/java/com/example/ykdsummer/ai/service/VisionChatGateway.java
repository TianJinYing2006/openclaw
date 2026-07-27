package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import java.util.List;

/** OpenAI-compatible Chat Completions path for one or more input images. */
public interface VisionChatGateway {

    LlmGateway.ModelReply generate(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            AiRequestBudget budget
    );
}
