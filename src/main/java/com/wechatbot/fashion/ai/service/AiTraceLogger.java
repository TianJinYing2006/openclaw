package com.wechatbot.fashion.ai.service;

import com.wechatbot.fashion.ai.config.AiTraceProperties;
import com.wechatbot.fashion.ai.model.AiFile;
import com.wechatbot.fashion.ai.model.AiImage;
import com.wechatbot.fashion.ai.orchestration.AgentSessionContext;
import com.wechatbot.fashion.weather.WeatherInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.web.client.RestClientResponseException;

/**
 * 把 AI 调试链路统一输出到 IDE 终端。
 *
 * <p>日志名称固定为 {@code AI_TRACE}，便于在控制台搜索。这里只记录经过截断和换行转义的
 * 文字、协议、附件元数据与工具结果，不记录 API Key、文件字节或 Base64。</p>
 */
@Component
public class AiTraceLogger {

    private static final Logger log = LoggerFactory.getLogger("AI_TRACE");

    private final AiTraceProperties properties;

    public AiTraceLogger(AiTraceProperties properties) {
        this.properties = properties;
    }

    public static AiTraceLogger disabled() {
        AiTraceProperties properties = new AiTraceProperties();
        properties.setEnabled(false);
        return new AiTraceLogger(properties);
    }

    public void request(
            String userId,
            String userPrompt,
            String modelPrompt,
            int historyCount,
            List<AiImage> images,
            List<AiFile> files
    ) {
        if (!properties.isEnabled()) {
            return;
        }
        log.info(
                "[会话开始] {} history={} images={} files={} message=\"{}\"",
                userLabel(userId),
                historyCount,
                size(images),
                fileSummary(files),
                shortPreview(userPrompt)
        );
    }

    public void route(String protocol, String reason, int imageCount, int fileCount) {
        route(null, protocol, reason, imageCount, fileCount);
    }

    public void route(String userId, String protocol, String reason, int imageCount, int fileCount) {
        if (properties.isEnabled()) {
            log.info(
                    "[模型路由] {} protocol={} reason={} images={} files={}",
                    userLabel(userId),
                    protocol,
                    reason,
                    imageCount,
                    fileCount
            );
        }
    }

    public void toolCall(String toolName, String arguments) {
        if (properties.isEnabled()) {
            log.info("[工具开始] {} tool={} input=\"{}\"", currentUserLabel(), toolName, shortPreview(arguments));
        }
    }

    /** 记录本轮实际交给模型的 Tool，不等同于模型一定会调用它们。 */
    public void toolCatalog(List<String> toolNames) {
        if (properties.isEnabled()) {
            log.info("[模型工具集] {} count={} sample={}", currentUserLabel(), toolNames == null ? 0 : toolNames.size(),
                    toolNames == null ? List.of() : toolNames.stream().limit(12).toList());
        }
    }

    public void toolResult(String toolName, Object result) {
        if (properties.isEnabled()) {
            log.info("[工具完成] {} tool={} result=\"{}\"", currentUserLabel(), toolName, preview(String.valueOf(result)));
        }
    }

    public void toolResult(String toolName, Object result, long durationMs) {
        if (properties.isEnabled()) {
            log.info("[工具完成] {} tool={} durationMs={} result=\"{}\"",
                    currentUserLabel(), toolName, durationMs, preview(String.valueOf(result)));
        }
    }

    public void toolFailure(String toolName, RuntimeException failure) {
        if (properties.isEnabled()) {
            log.warn("[工具失败] {} tool={} error={}", currentUserLabel(), toolName, failure.getClass().getSimpleName());
        }
    }

    public void toolFailure(String toolName, RuntimeException failure, long durationMs) {
        if (properties.isEnabled()) {
            log.warn("[工具失败] {} tool={} durationMs={} error={}",
                    currentUserLabel(), toolName, durationMs, failure.getClass().getSimpleName());
        }
    }

    public void modelReply(String protocol, String model, String text) {
        if (properties.isEnabled()) {
            log.debug("[模型原始响应] {} protocol={} actualModel={} answerChars={}",
                    currentUserLabel(), protocol, model, text == null ? 0 : text.length());
        }
    }

    public void modelCompleted(String userId, String protocol, String model, long durationMs,
                               AiModelUsage usage, String text) {
        if (!properties.isEnabled()) return;
        AiModelUsage safeUsage = usage == null ? AiModelUsage.unknown() : usage;
        log.info("[模型完成] {} protocol={} actualModel={} durationMs={} tokens={} reportedUsage={} answer=\"{}\"",
                userLabel(userId), protocol, model, Math.max(0, durationMs), safeUsage.totalTokens(), safeUsage.reported(),
                shortPreview(text));
    }

    public void modelFailure(String userId, String protocol, String model, long durationMs, String reason) {
        if (properties.isEnabled()) {
            log.warn("[模型失败] {} protocol={} actualModel={} durationMs={} reason={}", userLabel(userId), protocol,
                    model == null || model.isBlank() ? "未返回" : model, Math.max(0, durationMs), reason);
        }
    }

    /** 记录文件最终选择的协议，不输出文件正文或字节内容。 */
    public void fileRoute(String assetId, String format, boolean locallyExtracted, int byteCount) {
        if (properties.isEnabled()) {
            log.info("[文件路由] asset={} format={} localExtracted={} bytes={} nextProtocol={}",
                    assetId, format, locallyExtracted, byteCount,
                    locallyExtracted ? "Chat Completions + Tools" : "Responses");
        }
    }

    /** 把以前统一显示为“没有响应”的失败按可诊断类别写入 IDE 终端，不输出异常正文、密钥或输入。 */
    public void failure(String protocol, Throwable failure) {
        if (!properties.isEnabled()) {
            return;
        }
        Integer status = null;
        String category = "UPSTREAM_OR_NETWORK";
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException responseException) {
                status = responseException.getStatusCode().value();
                category = switch (status) {
                    case 401, 403 -> "AUTHENTICATION";
                    case 408, 504 -> "TIMEOUT";
                    case 429 -> "RATE_LIMIT";
                    default -> status >= 500 ? "UPSTREAM_5XX" : "HTTP_" + status;
                };
                break;
            }
            if (current instanceof SocketTimeoutException || current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase().contains("timeout")) {
                category = "TIMEOUT";
            }
        }
        log.warn("[上游失败] {} protocol={} category={} httpStatus={} type={}", currentUserLabel(),
                protocol, category, status == null ? "-" : status, failure.getClass().getSimpleName());
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = redactUrls(value).replace("\r", "\\r").replace("\n", "\\n");
        int limit = properties.getMaxTextLength();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…[已截断]";
    }

    private String shortPreview(String value) {
        String preview = preview(value);
        return preview.length() <= 180 ? preview : preview.substring(0, 180) + "…[摘要]";
    }

    /**
     * 日志脱敏：把 URL 中的查询参数（可能含 OSS 签名 / token）整体替换为占位符，
     * 只保留协议、主机和路径，便于定位又不泄露签名信息。
     */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://[^\\s\"'，。；）)]+");

    private static String redactUrls(String value) {
        if (value == null || value.indexOf("http") < 0) {
            return value;
        }
        java.util.regex.Matcher matcher = URL_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(redactUrl(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String redactUrl(String url) {
        int query = url.indexOf('?');
        if (query <= 0) {
            return url;
        }
        return url.substring(0, query) + "?[query-redacted]";
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String fileSummary(List<AiFile> files) {
        if (files == null || files.isEmpty()) {
            return "0";
        }
        return files.size() + "[" + files.stream()
                .map(file -> file.fileName() + ":" + file.bytes().length + "B")
                .collect(Collectors.joining(",")) + "]";
    }

    private static String currentUserLabel() {
        String userId = AgentSessionContext.currentUserId();
        return "anonymous".equals(userId) ? userLabel(null) : userLabel(userId);
    }

    private static String userLabel(String userId) {
        if (userId == null || userId.isBlank() || "unknown".equals(userId) || "anonymous".equals(userId)) {
            return "chatUser=unknown bot=-";
        }
        String instance = "-";
        if (userId.startsWith("managed:")) {
            int separator = userId.indexOf(':', "managed:".length());
            if (separator > 0) {
                instance = userId.substring("managed:".length(), Math.min("managed:".length() + 8, separator));
            }
        }
        return "chatUser=" + Integer.toHexString(userId.hashCode()) + " bot=" + instance;
    }
}
