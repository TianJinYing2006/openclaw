package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class ExpressToolsTest {

    @Test
    void usesAnonymousUapisTrackingAndPreservesTheProviderResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/tracking", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"tracking_number":"SF1234567890","carrier":{"name":"顺丰"},
                    "traces":[{"time":"2026-07-24 10:00:00","description":"已揽收"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ExpressTools tools = new ExpressTools(
                    "",
                    HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/tracking")
            );

            ToolCallback callback = ToolCallbacks.from(tools)[0];
            String result = callback.call("{\"logisticCode\":\"SF1234567890\",\"shipperCode\":\"sf\",\"phoneLast4\":\"1234\"}");

            assertThat(callback.getToolDefinition().name()).isEqualTo("query_express_tracking");
            assertThat(callback.getToolDefinition().inputSchema()).contains("logisticCode", "phoneLast4");
            assertThat(query.get()).isEqualTo("tracking_number=SF1234567890&carrier_code=sf&phone=1234");
            assertThat(authorization.get()).isNull();
            assertThat(result).contains("顺丰", "已揽收", "SF1234567890");
        } finally {
            server.stop(0);
        }
    }
}
