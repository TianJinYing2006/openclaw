package com.example.ykdsummer.ai.fashion.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 *   <li>JSON 响应解析与重试（6 种清洗策略逐级尝试，宽松解析 + 截断修复）</li>
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

    private final ChatModel chatModel;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public AgentLlmCaller(ChatModel chatModel, AiProperties aiProperties) {
        this.chatModel = chatModel;
        this.aiProperties = aiProperties;
        // 宽松 JSON 解析：LLM 输出常带尾逗号、单引号、未转义控制字符等问题，
        // 开启以下特性可大幅提升解析成功率
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .build();
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
            CompletableFuture<T> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> {
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
                cancelFuture(future);
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
                cancelFuture(future);
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
                        // mimo-v2.5 默认开启深度思考，思考内容占用 max_completion_tokens 额度，
                        // 导致实际输出为空。fashion 管道是结构化 JSON 输出，关闭思考可保证有返回值。
                        // 参数格式参考 MiMo 官方文档：extra_body: {"thinking": {"type": "disabled"}}
                        // 使用 HashMap 而非 Map.of，确保 Jackson 序列化嵌套对象时行为正确
                        .extraBody(buildThinkingDisabledBody())
                        .build()
        );

        long callStart = System.currentTimeMillis();
        // 超时取消后虚拟线程会收到中断信号，在发起网络请求前检查可快速退出
        if (Thread.currentThread().isInterrupted()) {
            log.debug("Agent LLM call cancelled before network request");
            return "";
        }
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
     * 解析 JSON，失败时自动清洗并重试。
     *
     * <p>清洗策略按力度递增，依次尝试 6 种组合：
     * <ol>
     *   <li>原始文本直接解析</li>
     *   <li>提取 ```json 代码块</li>
     *   <li>提取第一个 { 到最后一个 }</li>
     *   <li>修复后的原始文本（补全截断括号、去 BOM 等）</li>
     *   <li>修复后的代码块</li>
     *   <li>修复后的括号提取</li>
     * </ol>
     */
    private <T> T parseJsonWithRetry(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            log.warn("Empty LLM response");
            return null;
        }

        String codeBlock = extractJsonBlock(raw);
        String bracket = extractBrackets(raw);

        String[] candidates = {
                raw,                    // 原始
                codeBlock,              // ```json 代码块
                bracket,                // 第一个 { 到最后一个 }
                repairJson(raw),        // 修复后的原始
                repairJson(codeBlock),  // 修复后的代码块
                repairJson(bracket)     // 修复后的括号提取
        };
        String[] methods = {
                "direct", "code_block", "bracket",
                "repaired_direct", "repaired_code_block", "repaired_bracket"
        };

        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] == null || candidates[i].isBlank()) continue;
            T result = tryParse(candidates[i], type, methods[i]);
            if (result != null) return result;
        }

        log.warn("All {} JSON parse attempts failed. Raw: {}",
                candidates.length, raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
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
     * 修复 LLM 输出中常见的 JSON 格式问题。
     *
     * <p>处理 ObjectMapper 宽松模式无法覆盖的问题：
     * <ul>
     *   <li>移除 BOM 头</li>
     *   <li>移除残留的 Markdown 代码块标记</li>
     *   <li>补全因 max_tokens 截断导致缺失的闭合括号</li>
     * </ul>
     */
    private String repairJson(String json) {
        if (json == null || json.isBlank()) return null;
        String repaired = json.strip();
        // 移除 BOM
        if (repaired.startsWith("\uFEFF")) {
            repaired = repaired.substring(1);
        }
        // 移除残留的 Markdown 代码块标记
        repaired = repaired.replaceAll("^```(?:json)?\\s*", "");
        repaired = repaired.replaceAll("\\s*```$", "");
        // 补全因截断导致缺失的闭合括号
        repaired = closeUnclosedBraces(repaired);
        return repaired;
    }

    /**
     * 统计未闭合的大括号和方括号，在末尾按正确顺序补全。
     *
     * <p>可挽救因 max_tokens 截断导致的不完整 JSON，例如：
     * {@code {"a": 1, "b": [2, 3} → 补全为 → {"a": 1, "b": [2, 3]}
     */
    private String closeUnclosedBraces(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            switch (c) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
            }
        }

        if (braces < 0 || brackets < 0) return json; // 结构已损坏，无法简单修复
        if (braces == 0 && brackets == 0) return json; // 已平衡

        StringBuilder sb = new StringBuilder(json);
        while (brackets-- > 0) sb.append(']');
        while (braces-- > 0) sb.append('}');
        return sb.toString();
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

    /**
     * 取消超时的 Future，避免底层虚拟线程继续空转浪费资源。
     *
     * <p>{@code mayInterruptIfRunning=true} 会给运行中的线程发送中断信号；
     * {@code callLlm} 在网络请求前会检查中断状态，已返回的请求无法中断但结果会被丢弃。
     */
    private void cancelFuture(java.util.concurrent.Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
            log.debug("Cancelled timed-out LLM future");
        }
    }

    /**
     * 构建 mimo-v2.5 关闭深度思考的 extraBody。
     *
     * <p>使用可变 HashMap 构建嵌套结构，避免 Map.of 创建的不可变 Map
     * 在 Jackson @JsonAnyGetter 序列化时出现兼容性问题。
     *
     * <p>最终展平到请求 JSON 顶层的效果：{@code "thinking": {"type": "disabled"}}
     */
    private java.util.Map<String, Object> buildThinkingDisabledBody() {
        java.util.Map<String, Object> thinking = new java.util.HashMap<>();
        thinking.put("type", "disabled");
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("thinking", thinking);
        return body;
    }
}
