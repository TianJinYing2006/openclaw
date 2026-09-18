package com.wechatbot.fashion.ai.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 鉴权接线测试：配置 {@code app.mcp.auth-token} 后，客户端请求应带上
 * {@code Authorization: Bearer <token>}。
 *
 * <p>用一个立即返回 500 的本地 HTTP server 捕获请求头；MCP 初始化必然失败，但鉴权头在此之前已发出。
 */
class McpConnectionManagerAuthTest {

    @Test
    void sendsBearerTokenWhenConfigured() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                exchange.getRequestBody().readAllBytes();
            } catch (Exception ignored) {
                // 忽略：只需要拿到请求头
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.ai.mcp.client.streamable-http.connections.unified-mcp.url",
                            "http://127.0.0.1:" + port)
                    .withProperty("spring.ai.mcp.client.streamable-http.connections.unified-mcp.endpoint", "/mcp")
                    .withProperty("spring.ai.mcp.client.request-timeout", "2s")
                    .withProperty("app.mcp.auth-token", "secret-token");

            new McpConnectionManager(environment).current();

            assertThat(authorization.get()).isEqualTo("Bearer secret-token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsAuthorizationWhenTokenBlank() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>("unset");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.ai.mcp.client.streamable-http.connections.unified-mcp.url",
                            "http://127.0.0.1:" + port)
                    .withProperty("spring.ai.mcp.client.streamable-http.connections.unified-mcp.endpoint", "/mcp")
                    .withProperty("spring.ai.mcp.client.request-timeout", "2s")
                    .withProperty("app.mcp.auth-token", "");

            new McpConnectionManager(environment).current();

            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
