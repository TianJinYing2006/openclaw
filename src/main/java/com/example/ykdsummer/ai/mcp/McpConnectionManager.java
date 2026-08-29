package com.example.ykdsummer.ai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * MCP 连接管理器：自管理 {@link SyncMcpToolCallbackProvider} 的生命周期。
 *
 * <p>背景：Spring AI 的 MCP client 自动配置在启动时即连接外部 server，server 重启后
 * 客户端不自动重连（ClosedChannelException），且 Bot 启动强依赖 MCP server 先启动。
 * 本管理器改为懒初始化 + 调用失败自动重建，消除这两类问题。</p>
 *
 * <p>连接参数读取 {@code spring.ai.mcp.client.*} 配置（url / endpoint / request-timeout），
 * 由调用方通过 {@link #callTool} 统一入口调用，工具不存在时返回 {@code null}。</p>
 */
@Component
public class McpConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionManager.class);

    private static final String MCP_URL_PROP = "spring.ai.mcp.client.streamable-http.connections.unified-mcp.url";
    private static final String MCP_ENDPOINT_PROP = "spring.ai.mcp.client.streamable-http.connections.unified-mcp.endpoint";
    private static final String MCP_TIMEOUT_PROP = "spring.ai.mcp.client.request-timeout";

    private final String baseUrl;
    private final String endpoint;
    private final Duration requestTimeout;

    private final AtomicReference<SyncMcpToolCallbackProvider> providerRef = new AtomicReference<>();
    private final AtomicReference<List<McpSyncClient>> clientsRef = new AtomicReference<>(List.of());
    private final Object lock = new Object();

    public McpConnectionManager(Environment environment) {
        this.baseUrl = environment.getProperty(MCP_URL_PROP, "");
        this.endpoint = environment.getProperty(MCP_ENDPOINT_PROP, "/mcp");
        this.requestTimeout = parseDuration(environment.getProperty(MCP_TIMEOUT_PROP, "300s"));
    }

    /**
     * 调用 MCP 工具。连接失败时重建连接并重试一次；工具不存在或未配置时返回 {@code null}。
     */
    public String callTool(String toolName, String jsonArgs) {
        try {
            return invoke(toolName, jsonArgs);
        } catch (RuntimeException failure) {
            if (!isConnectionFailure(failure)) {
                throw failure;
            }
            log.warn("MCP 调用失败（疑似连接中断），重建连接后重试一次: tool={}, cause={}",
                    toolName, rootMessage(failure));
            refresh();
            return invoke(toolName, jsonArgs);
        }
    }

    /** 当前可用的工具回调提供者；未配置或连接未建立时为 {@code null}。 */
    public SyncMcpToolCallbackProvider current() {
        SyncMcpToolCallbackProvider provider = providerRef.get();
        if (provider == null) {
            synchronized (lock) {
                if (providerRef.get() == null) {
                    rebuild();
                }
            }
        }
        return providerRef.get();
    }

    /** 关闭旧连接并重建（供自愈重试与运维调用）。 */
    public void refresh() {
        synchronized (lock) {
            rebuild();
        }
    }

    private String invoke(String toolName, String jsonArgs) {
        SyncMcpToolCallbackProvider provider = current();
        if (provider == null) {
            return null;
        }
        ToolCallback tool = McpToolSupport.findTool(provider, toolName);
        if (tool == null) {
            return null;
        }
        return tool.call(jsonArgs);
    }

    private void rebuild() {
        closeClients(clientsRef.getAndSet(List.of()));
        if (baseUrl.isBlank()) {
            providerRef.set(null);
            log.warn("MCP 未配置（{} 为空），联网/天气/抠图/试衣等 MCP 工具不可用", MCP_URL_PROP);
            return;
        }
        try {
            String url = endpoint.startsWith("/") ? baseUrl + endpoint : baseUrl + "/" + endpoint;
            McpClientTransport transport = HttpClientStreamableHttpTransport.builder(url)
                    .connectTimeout(requestTimeout)
                    // 请求级超时兜底：MCP SDK 同步调用可能无限阻塞，HttpRequest.timeout 确保
                    // 长耗时工具（照片识别/抠图/试衣）在异常时能抛出 HttpTimeoutException 走失败重试，
                    // 而不是永久挂起占用后台线程。
                    .customizeRequest(request -> request.timeout(requestTimeout))
                    // 强制 HTTP/1.1：Java HttpClient 默认发 h2c 升级，hypercorn 长连接复用后
                    // 半关闭的 HTTP/2 流会导致响应永远等不到（偶发挂起），HTTP/1.1 连接语义更可靠。
                    .customizeClient(client -> client.version(java.net.http.HttpClient.Version.HTTP_1_1))
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .build();
            client.initialize();
            List<McpSyncClient> clients = new ArrayList<>();
            clients.add(client);
            clientsRef.set(clients);
            providerRef.set(new SyncMcpToolCallbackProvider(clients));
            log.info("MCP 连接已建立: url={}", url);
        } catch (Exception failure) {
            providerRef.set(null);
            log.warn("MCP 连接初始化失败，将在下次调用时重试: {}", rootMessage(failure));
        }
    }

    private static void closeClients(List<McpSyncClient> clients) {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 关闭失败不阻断重建
            }
        }
    }

    private static boolean isConnectionFailure(RuntimeException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IOException
                    || current instanceof ConnectException
                    || current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message.strip();
    }

    private static Duration parseDuration(String value) {
        try {
            String normalized = value == null || value.isBlank() ? "300s" : value.strip().toUpperCase();
            return Duration.parse(normalized.startsWith("P") ? normalized : "PT" + normalized);
        } catch (Exception exception) {
            return Duration.ofSeconds(300);
        }
    }
}
