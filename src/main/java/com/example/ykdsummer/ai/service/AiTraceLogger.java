package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiTraceProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.weather.WeatherInfo;
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
                "[请求] user={} history={} images={} files={} 用户消息=\"{}\" 模型实际输入=\"{}\"",
                anonymize(userId),
                historyCount,
                size(images),
                fileSummary(files),
                preview(userPrompt),
                preview(modelPrompt)
        );
    }

    public void route(String protocol, String reason, int imageCount, int fileCount) {
        if (properties.isEnabled()) {
            log.info(
                    "[协议] {}，原因={}，images={}，files={}",
                    protocol,
                    reason,
                    imageCount,
                    fileCount
            );
        }
    }

    public void toolCall(String toolName, String arguments) {
        if (properties.isEnabled()) {
            log.info("[工具调用] name={} arguments=\"{}\"", toolName, preview(arguments));
        }
    }

    public void toolResult(String toolName, Object result) {
        if (properties.isEnabled()) {
            log.info("[工具结果] name={} result=\"{}\"", toolName, preview(String.valueOf(result)));
        }
    }

    public void toolFailure(String toolName, RuntimeException failure) {
        if (properties.isEnabled()) {
            log.warn("[工具失败] name={} error={}", toolName, failure.getClass().getSimpleName());
        }
    }

    public void modelReply(String protocol, String model, String text) {
        if (properties.isEnabled()) {
            log.info("[模型回复] protocol={} model={} text=\"{}\"", protocol, model, preview(text));
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
        log.warn("[失败] protocol={} category={} httpStatus={} type={}",
                protocol, category, status == null ? "-" : status, failure.getClass().getSimpleName());
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        int limit = properties.getMaxTextLength();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…[已截断]";
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

    private static String anonymize(String userId) {
        return userId == null ? "unknown" : Integer.toHexString(userId.hashCode());
    }
}
