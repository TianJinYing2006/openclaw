package com.wechatbot.fashion.ai.fashion.look.agent;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wechatbot.fashion.ai.config.AiProperties;
import com.wechatbot.fashion.graph.trajectory.AgentTrajectoryRecorder;
import com.wechatbot.fashion.graph.trajectory.TrajectoryRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *   <li>结构化输出（Spring AI {@link BeanOutputConverter}）反序列化（JSON 响应解析与重试）</li>
 *   <li>超时控制（通过 CompletableFuture + timeout）</li>
 *   <li>异常分类：网络超时 / LLM 报错 / 解析失败</li>
 *   <li>不注入任何工具，保证 Agent 推理纯净性</li>
 * </ul>
 *
 * <p>P1（2026-08-31）：解析从手搓宽松 JSON 迁移为 Spring AI 结构化输出
 * {@link BeanOutputConverter}（复用宽松 ObjectMapper，兼容 LLM 尾逗号/单引号等脏输出），
 * 保留超时/重试/thinking-disabled/JSON-Mode 全部既有能力。</p>
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
    /** Agent 轨迹记录器；Spring 存在时注入，测试直接 new 时保持 noop。 */
    private volatile AgentTrajectoryRecorder trajectoryRecorder = AgentTrajectoryRecorder.noop();
    /** 单次 run 执行预算跟踪器；未注入时不限预算。 */
    private volatile com.wechatbot.fashion.graph.budget.RunBudgetTracker runBudgetTracker;

    /** LLM 原始结果 + token 用量，供轨迹记录（parse 前后都要保留 usage）。 */
    private record LlmResult(String text, long promptTokens, long completionTokens, long totalTokens) {
    }

    /** 一次「调用 + 解析」的中间结果。 */
    private record Attempt<T>(T parsed, LlmResult llm) {
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrajectoryRecorder(AgentTrajectoryRecorder trajectoryRecorder) {
        if (trajectoryRecorder != null) {
            this.trajectoryRecorder = trajectoryRecorder;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunBudgetTracker(com.wechatbot.fashion.graph.budget.RunBudgetTracker runBudgetTracker) {
        this.runBudgetTracker = runBudgetTracker;
    }

    public AgentLlmCaller(ChatModel chatModel, AiProperties aiProperties,
                          @Qualifier("agentLlmCallerExecutor") ExecutorService executor) {
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
        this.executor = executor;
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
        TrajectoryRunContext.Ref ref = TrajectoryRunContext.current();
        String agent = outputType.getSimpleName();
        if (!budgetAllows(ref, agent, startTime)) {
            return null;
        }

        for (int networkAttempt = 0; networkAttempt <= NETWORK_RETRY_MAX; networkAttempt++) {
            CompletableFuture<Attempt<T>> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    LlmResult llm = callLlm(systemPrompt, userMessage, maxTokens);
                    return new Attempt<>(parseJsonWithRetry(llm.text(), outputType), llm);
                }, executor);

                Attempt<T> attempt = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                T result = attempt.parsed();
                recordTokens(ref, attempt.llm());

                long elapsed = System.currentTimeMillis() - startTime;
                if (result != null) {
                    log.info("Agent LLM call succeeded in {}ms (networkAttempt={})", elapsed, networkAttempt);
                    recordModel(ref, agent, attempt.llm(), elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_SUCCESS, null);
                } else {
                    recordModel(ref, agent, attempt.llm(), elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_FAILED, "parse-failed");
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
                    recordModel(ref, agent, null, elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_FAILED, concise(cause));
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
        recordModel(ref, agent, null, System.currentTimeMillis() - startTime,
                AgentTrajectoryRecorder.Step.STATUS_FAILED, "timeout/network");
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
     * 贪婪采样调用（temperature=0）：用于对结果可复现性有要求的分析（如 QueryAnalyzer）。
     *
     * <p>普通 Agent 调用保留默认采样以维持多样性；QueryAnalyzer 的检索词构造需要确定性，
     * 否则同一查询每次分析不同会导致检索命中率剧烈波动（实测 ±37pt）。
     * 其余逻辑（重试/超时/JSON 解析）与 {@link #callAgent} 完全一致。
     */
    public <T> T callAgentGreedy(String systemPrompt, String userMessage,
                                 Class<T> outputType, int maxTokens, Duration timeout) {
        long startTime = System.currentTimeMillis();
        TrajectoryRunContext.Ref ref = TrajectoryRunContext.current();
        String agent = outputType.getSimpleName();
        if (!budgetAllows(ref, agent, startTime)) {
            return null;
        }

        for (int networkAttempt = 0; networkAttempt <= NETWORK_RETRY_MAX; networkAttempt++) {
            CompletableFuture<Attempt<T>> future = null;
            try {
                future = CompletableFuture.supplyAsync(() -> {
                    LlmResult llm = callLlm(systemPrompt, userMessage, maxTokens, true);
                    return new Attempt<>(parseJsonWithRetry(llm.text(), outputType), llm);
                }, executor);

                Attempt<T> attempt = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                T result = attempt.parsed();
                recordTokens(ref, attempt.llm());

                long elapsed = System.currentTimeMillis() - startTime;
                if (result != null) {
                    log.info("Agent greedy LLM call succeeded in {}ms (networkAttempt={})", elapsed, networkAttempt);
                    recordModel(ref, agent, attempt.llm(), elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_SUCCESS, null);
                } else {
                    recordModel(ref, agent, attempt.llm(), elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_FAILED, "parse-failed");
                }
                return result;

            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("Agent greedy LLM call timed out after {}ms (attempt {}/{})",
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
                    recordModel(ref, agent, null, elapsed,
                            AgentTrajectoryRecorder.Step.STATUS_FAILED, concise(cause));
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Agent greedy LLM call interrupted");
                cancelFuture(future);
                return null;
            }
        }

        log.error("Agent greedy LLM call failed after all retries");
        recordModel(ref, agent, null, System.currentTimeMillis() - startTime,
                AgentTrajectoryRecorder.Step.STATUS_FAILED, "timeout/network");
        return null;
    }

    /**
     * 实际调用 LLM，返回原始文本。
     */
    private LlmResult callLlm(String systemPrompt, String userMessage, int maxTokens) {
        return callLlm(systemPrompt, userMessage, maxTokens, false);
    }

    /**
     * 实际调用 LLM，返回原始文本。
     *
     * @param greedy 是否贪婪采样（temperature=0），用于需要确定性的分析调用
     */
    private LlmResult callLlm(String systemPrompt, String userMessage, int maxTokens, boolean greedy) {
        // 穿搭管道优先使用专用快速模型（app.ai.fashion-model），未配置时回退主模型
        String model = (aiProperties.getFashionModel() == null || aiProperties.getFashionModel().isBlank())
                ? aiProperties.getModel()
                : aiProperties.getFashionModel();
        log.info("Agent LLM call start: model={}, maxTokens={}, greedy={}, systemChars={}, userChars={}",
                model, maxTokens, greedy, systemPrompt.length(), userMessage.length());
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userMessage)
                ),
                OpenAiChatOptions.builder()
                        .model(model)
                        .maxCompletionTokens(Math.max(1, maxTokens))
                        .store(false)
                        .temperature(greedy ? 0.0 : null)
                        // 不同模型关闭思考的参数格式不同：
                        // qwen 系列用 enable_thinking=false，mimo 系列用 thinking={"type":"disabled"}。
                        // 按模型名选择对应参数，避免思考内容占用 max_completion_tokens 导致输出为空。
                        .extraBody(buildThinkingDisabledBody(model))
                        .build()
        );

        long callStart = System.currentTimeMillis();
        // 超时取消后虚拟线程会收到中断信号，在发起网络请求前检查可快速退出
        if (Thread.currentThread().isInterrupted()) {
            log.debug("Agent LLM call cancelled before network request");
            return new LlmResult("", 0, 0, 0);
        }
        ChatResponse response = chatModel.call(prompt);
        long callElapsed = System.currentTimeMillis() - callStart;
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            log.warn("Agent LLM call returned empty response after {}ms", callElapsed);
            return new LlmResult("", 0, 0, 0);
        }
        String text = response.getResult().getOutput().getText();
        // 记录 token 消耗（计费按实际生成 tokens，maxTokens 只是上限）
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        long promptTokens = 0;
        long completionTokens = 0;
        long totalTokens = 0;
        if (usage != null) {
            promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            totalTokens = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
            log.info("Agent LLM tokens: prompt={}, completion={}, total={} ({}ms)",
                    promptTokens, completionTokens, totalTokens, callElapsed);
        } else {
            log.info("Agent LLM call done in {}ms (usage unavailable)", callElapsed);
        }
        return new LlmResult(text == null ? "" : text.strip(), promptTokens, completionTokens, totalTokens);
    }

    /** 预算护栏：不允许时记录失败 step 并返回 false，调用方应直接返回 null。 */
    private boolean budgetAllows(TrajectoryRunContext.Ref ref, String agent, long startedAt) {
        if (runBudgetTracker == null || ref == null || !ref.present()) {
            return true;
        }
        if (runBudgetTracker.allowModelCall(ref.runId())) {
            return true;
        }
        String reason = runBudgetTracker.exceededReason(ref.runId()).orElse("budget");
        log.warn("Agent LLM call blocked by run budget ({}): runId={}", reason, ref.runId());
        recordModel(ref, agent, null, System.currentTimeMillis() - startedAt,
                AgentTrajectoryRecorder.Step.STATUS_FAILED, "budget:" + reason);
        return false;
    }

    /** 统计一次模型调用消耗的 token 到 run 预算。 */
    private void recordTokens(TrajectoryRunContext.Ref ref, LlmResult llm) {
        if (runBudgetTracker != null && ref != null && ref.present() && llm != null) {
            runBudgetTracker.addTokens(ref.runId(), llm.totalTokens());
        }
    }

    /** 记录一次 MODEL step；runId 缺失（如直接单测）时静默跳过。 */
    private void recordModel(TrajectoryRunContext.Ref ref, String agent, LlmResult llm,
                             long durationMs, String status, String error) {
        if (ref == null || !ref.present()) {
            return;
        }
        long prompt = llm == null ? 0 : llm.promptTokens();
        long completion = llm == null ? 0 : llm.completionTokens();
        long total = llm == null ? 0 : llm.totalTokens();
        trajectoryRecorder.recordStep(new AgentTrajectoryRecorder.Step(
                ref.runId(),
                ref.node() == null ? "" : ref.node(),
                AgentTrajectoryRecorder.Step.TYPE_MODEL,
                status,
                agent,
                null,
                "agent=" + agent,
                error == null ? "ok" : error,
                prompt, completion, total,
                durationMs,
                error));
    }

    /** 异常简短原因（截断，避免长堆栈进入轨迹）。 */
    private static String concise(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        String single = msg.replace('\n', ' ').trim();
        return single.length() > 120 ? single.substring(0, 120) : single;
    }

    /**
     * 解析 LLM 输出为指定类型（Spring AI 结构化输出）。
     *
     * <p>P1：使用 {@link BeanOutputConverter}（复用宽松 ObjectMapper，容忍尾逗号/单引号等脏输出；
     * 通过 {@code response_format: json_object} JSON-Mode 让模型输出严格 JSON）。
     * 不再手搓清洗链，只保留两种兜底：直接解析 + ```json 代码块提取。</p>
     */
    private <T> T parseJsonWithRetry(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            log.warn("Empty LLM response");
            return null;
        }
        T direct = tryParse(raw, type, "direct");
        if (direct != null) return direct;
        String block = extractJsonBlock(raw);
        if (block == null) {
            log.warn("No JSON content found in LLM response");
            return null;
        }
        return tryParse(block, type, "code_block");
    }

    private <T> T tryParse(String json, Class<T> type, String method) {
        if (json == null || json.isBlank()) return null;
        // 1. Spring AI 结构化输出：按 JSON-schema 反序列化（字段齐全则规范）。
        try {
            BeanOutputConverter<T> converter = new BeanOutputConverter<>(type, objectMapper);
            T strict = converter.convert(json);
            if (strict != null) {
                log.debug("Structured output parse succeeded via {}", method);
                return strict;
            }
            log.debug("Structured output parse returned null via {}", method);
        } catch (Exception e) {
            log.debug("Structured output strict parse failed via {}: {}", method, e.getMessage());
        }
        // 2. 宽松兜底：兼容 LLM 漏字段/意外结构（结构化输出失败不阻断生产路径，字段以默认值兜底）。
        try {
            T result = objectMapper.readValue(json, type);
            log.debug("Lenient fallback parse succeeded via {}", method);
            return result;
        } catch (Exception e2) {
            log.debug("Lenient fallback parse failed via {}: {}", method, e2.getMessage());
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
     * 构建关闭深度思考的 extraBody，按模型名选择兼容的参数格式。
     *
     * <ul>
     *   <li>qwen 系列（如 qwen3.7-flash）：{@code "enable_thinking": false}</li>
     *   <li>mimo 系列：{@code "thinking": {"type": "disabled"}}</li>
     *   <li>未知模型：两种都带上，OpenAI 兼容网关通常忽略未知字段</li>
     * </ul>
     *
     * <p>使用可变 HashMap 构建嵌套结构，避免 Map.of 创建的不可变 Map
     * 在 Jackson @JsonAnyGetter 序列化时出现兼容性问题。
     */
    private java.util.Map<String, Object> buildThinkingDisabledBody(String model) {
        String normalized = model == null ? "" : model.toLowerCase();
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (normalized.contains("mimo")) {
            java.util.Map<String, Object> thinking = new java.util.HashMap<>();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        } else if (normalized.contains("qwen")) {
            body.put("enable_thinking", false);
        } else {
            body.put("enable_thinking", false);
            java.util.Map<String, Object> thinking = new java.util.HashMap<>();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        }
        // 百炼 Qwen 支持 response_format=json_object（JSON Mode，需关闭 thinking），
        // 让模型直接输出严格 JSON，从源头减少解析失败。
        if (normalized.contains("qwen")) {
            java.util.Map<String, Object> jsonMode = new java.util.HashMap<>();
            jsonMode.put("type", "json_object");
            body.put("response_format", jsonMode);
        }
        return body;
    }
}
