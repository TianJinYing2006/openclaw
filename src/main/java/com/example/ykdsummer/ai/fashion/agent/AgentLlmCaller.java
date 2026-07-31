package com.example.ykdsummer.ai.fashion.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.example.ykdsummer.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * 统一的 Agent LLM 调用器。所有 Agent 通过此类调用大模型。
 *
 * <p>核心能力：
 * <ul>
 *   <li>统一的 system + user prompt 构建</li>
 *   <li>JSON 响应解析与重试（最多重试 1 次）</li>
 *   <li>超时控制（通过 CompletableFuture + timeout）</li>
 *   <li>不注入任何工具，保证 Agent 推理纯净性</li>
 * </ul>
 *
 * <p>这是整个穿搭 Agent 管道的基座。所有 Agent 的 execute() 方法都通过此类发起 LLM 调用。
 */
@Component
public class AgentLlmCaller {

    private static final Logger log = LoggerFactory.getLogger(AgentLlmCaller.class);

    private final ChatModel chatModel;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AgentLlmCaller(ChatModel chatModel, AiProperties aiProperties) {
        this.chatModel = chatModel;
        this.aiProperties = aiProperties;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 调用 LLM 并解析为指定类型。
     *
     * @param systemPrompt  Agent 的系统提示词
     * @param userMessage   用户消息（结构化上下文的 JSON 或文本）
     * @param outputType    期望的输出类型
     * @param maxTokens     输出 token 上限
     * @param timeout       超时时间
     * @return 解析后的对象，失败返回 null
     */
    public <T> T callAgent(String systemPrompt, String userMessage,
                           Class<T> outputType, int maxTokens, Duration timeout) {
        try {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                String raw = callLlm(systemPrompt, userMessage, maxTokens);
                return parseJson(raw, outputType);
            }, executor);

            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            log.warn("Agent LLM call timed out after {}ms", timeout.toMillis());
            return null;
        } catch (ExecutionException e) {
            log.warn("Agent LLM call failed: {}", e.getCause().getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Agent LLM call interrupted");
            return null;
        }
    }

    /**
     * 简化重载：使用默认 token 上限和 30s 超时。
     */
    public <T> T callAgent(String systemPrompt, String userMessage, Class<T> outputType) {
        return callAgent(systemPrompt, userMessage, outputType,
                aiProperties.getMaxCompletionTokens(), Duration.ofSeconds(30));
    }

    /**
     * 实际调用 LLM，返回原始文本。
     */
    private String callLlm(String systemPrompt, String userMessage, int maxTokens) {
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userMessage)
                ),
                OpenAiChatOptions.builder()
                        .model(aiProperties.getModel())
                        .maxCompletionTokens(Math.max(1, maxTokens))
                        .store(false)
                        .build()
        );

        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.strip();
    }

    /**
     * 解析 JSON，失败时重试一次（先尝试提取 JSON 块）。
     */
    private <T> T parseJson(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            log.warn("Empty LLM response, cannot parse as JSON");
            return null;
        }

        // 第一次尝试：直接解析
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            log.debug("Direct JSON parse failed, trying to extract JSON block");
        }

        // 第二次尝试：提取 ```json ... ``` 代码块
        String extracted = extractJsonBlock(raw);
        if (extracted != null) {
            try {
                return objectMapper.readValue(extracted, type);
            } catch (Exception e) {
                log.debug("JSON block extraction parse failed");
            }
        }

        // 第三次尝试：找到第一个 { 到最后一个 }
        String bracket = extractBrackets(raw);
        if (bracket != null) {
            try {
                return objectMapper.readValue(bracket, type);
            } catch (Exception e) {
                log.warn("All JSON parse attempts failed. Raw response: {}",
                        raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            }
        }

        return null;
    }

    private String extractJsonBlock(String text) {
        int start = text.indexOf("```json");
        if (start >= 0) {
            int contentStart = start + 7;
            int end = text.indexOf("```", contentStart);
            if (end > contentStart) {
                return text.substring(contentStart, end).strip();
            }
        }
        return null;
    }

    private String extractBrackets(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }
}
