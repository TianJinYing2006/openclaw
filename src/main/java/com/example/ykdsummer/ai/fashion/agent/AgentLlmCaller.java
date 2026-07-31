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
 *   <li>JSON 响应解析与重试（解析失败重试 2 次，网络超时重试 1 次）</li>
 *   <li>超时控制（通过 CompletableFuture + timeout）</li>
 *   <li>异常分类：网络超时 / LLM 报错 / 解析失败</li>
 *   <li>不注入任何工具，保证 Agent 推理纯净性</li>
 * </ul>
 */
@Component
public class AgentLlmCaller {

    private static final Logger log = LoggerFactory.getLogger(AgentLlmCaller.class);

    /** 网络超时重试次数 */
    private static final int NETWORK_RETRY_MAX = 1;
    /** JSON 解析失败重试次数 */
    private static final int PARSE_RETRY_MAX = 2;

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
     * <p>降级策略：
     * <ol>
     *   <li>网络超时 → 重试 1 次</li>
     *   <li>LLM 返回非 JSON → 自动清洗后重试 2 次</li>
     *   <li>全部失败 → 返回 null</li>
     * </ol>
     */
    public <T> T callAgent(String systemPrompt, String userMessage,
                           Class<T> outputType, int maxTokens, Duration timeout) {
        long startTime = System.currentTimeMillis();

        for (int networkAttempt = 0; networkAttempt <= NETWORK_RETRY_MAX; networkAttempt++) {
            try {
                CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                    String raw = callLlm(systemPrompt, userMessage, maxTokens);
                    return parseJsonWithRetry(raw, outputType);
                }, executor);

                T result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                if (result != null) {
                    log.info("Agent LLM call succeeded in {}ms (networkAttempt={})", elapsed, networkAttempt);
                }
                return result;

            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("Agent LLM call timed out after {}ms (attempt {}/{})",
                        elapsed, networkAttempt + 1, NETWORK_RETRY_MAX + 1);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                long elapsed = System.currentTimeMillis() - startTime;
                if (isNetworkError(cause)) {
                    log.warn("Network error after {}ms (attempt {}/{}): {}",
                            elapsed, networkAttempt + 1, NETWORK_RETRY_MAX + 1, cause.getMessage());
                } else {
                    log.warn("LLM call error after {}ms: {}", elapsed, cause.getMessage());
                    return null; // 非网络错误不重试
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Agent LLM call interrupted");
                return null;
            }
        }

        log.error("Agent LLM call failed after all retries");
        return null;
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
        // 穿搭管道优先使用专用快速模型（app.ai.fashion-model），未配置时回退主模型
        String model = (aiProperties.getFashionModel() == null || aiProperties.getFashionModel().isBlank())
                ? aiProperties.getModel()
                : aiProperties.getFashionModel();
        log.info("Agent LLM call start: model={}, maxTokens={}, systemChars={}, userChars={}",
                model, maxTokens, systemPrompt.length(), userMessage.length());
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userMessage)
                ),
                OpenAiChatOptions.builder()
                        .model(model)
                        .maxCompletionTokens(Math.max(1, maxTokens))
                        .store(false)
                        // qwen3 系列默认开启 thinking 模式，思考内容占用 tokens 且 content 为空；
                        // fashion 管道是结构化 JSON 输出，关闭思考可大幅提速并保证有返回值
                        .extraBody(java.util.Map.of("enable_thinking", false))
                        .build()
        );

        long callStart = System.currentTimeMillis();
        ChatResponse response = chatModel.call(prompt);
        long callElapsed = System.currentTimeMillis() - callStart;
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            log.warn("Agent LLM call returned empty response after {}ms", callElapsed);
            return "";
        }
        String text = response.getResult().getOutput().getText();
        // 记录 token 消耗（计费按实际生成 tokens，maxTokens 只是上限）
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage != null) {
            log.info("Agent LLM tokens: prompt={}, completion={}, total={} ({}ms)",
                    usage.getPromptTokens(), usage.getCompletionTokens(),
                    usage.getTotalTokens(), callElapsed);
        } else {
            log.info("Agent LLM call done in {}ms (usage unavailable)", callElapsed);
        }
        return text == null ? "" : text.strip();
    }

    /**
     * 解析 JSON，失败时自动清洗并重试（最多 PARSE_RETRY_MAX 次）。
     */
    private <T> T parseJsonWithRetry(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            log.warn("Empty LLM response");
            return null;
        }

        // 第一次：直接解析
        T result = tryParse(raw, type, "direct");
        if (result != null) return result;

        // 第二次：提取 ```json ... ``` 代码块
        String extracted = extractJsonBlock(raw);
        if (extracted != null) {
            result = tryParse(extracted, type, "code_block");
            if (result != null) return result;
        }

        // 第三次：第一个 { 到最后一个 }
        String bracket = extractBrackets(raw);
        if (bracket != null) {
            result = tryParse(bracket, type, "bracket");
            if (result != null) return result;
        }

        log.warn("All {} JSON parse attempts failed. Raw: {}",
                PARSE_RETRY_MAX + 1, raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
        return null;
    }

    private <T> T tryParse(String json, Class<T> type, String method) {
        try {
            T result = objectMapper.readValue(json, type);
            log.debug("JSON parse succeeded via {}", method);
            return result;
        } catch (Exception e) {
            log.debug("JSON parse failed via {}: {}", method, e.getMessage());
            return null;
        }
    }

    private String extractJsonBlock(String text) {
        int start = text.indexOf("```json");
        if (start < 0) start = text.indexOf("```");
        if (start >= 0) {
            int contentStart = text.indexOf("\n", start);
            if (contentStart < 0) contentStart = start + 3;
            else contentStart++;
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

    /**
     * 判断是否为网络类错误（值得重试）。
     */
    private boolean isNetworkError(Throwable cause) {
        if (cause instanceof TimeoutException) return true;
        if (cause instanceof java.net.SocketTimeoutException) return true;
        if (cause instanceof java.net.ConnectException) return true;
        if (cause instanceof java.io.IOException) return true;
        String msg = cause.getMessage();
        if (msg != null) {
            msg = msg.toLowerCase();
            return msg.contains("timeout") || msg.contains("connection")
                    || msg.contains("socket") || msg.contains("reset");
        }
        return false;
    }
}
