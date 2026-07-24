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

class PhoneInfoToolsTest {

    @Test
    void exposesPhoneLookupAndUsesAnonymousVisitorAccessByDefault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/phoneinfo", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] response = "{\"province\":\"北京\",\"city\":\"北京\",\"sp\":\"移动\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            PhoneInfoTools tools = new PhoneInfoTools(
                    "",
                    HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/phoneinfo")
            );

            ToolCallback callback = ToolCallbacks.from(tools)[0];
            String result = callback.call("{\"phone\":\"13800138000\"}");

            assertThat(callback.getToolDefinition().name()).isEqualTo("get_phone_info");
            assertThat(callback.getToolDefinition().inputSchema()).contains("phone", "11");
            assertThat(result).contains("北京", "移动");
            assertThat(query.get()).isEqualTo("phone=13800138000");
            assertThat(authorization.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidPhoneBeforeCallingTheNetwork() {
        PhoneInfoTools tools = new PhoneInfoTools("", HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1"));

        assertThat(tools.getPhoneInfo("123")).isEqualTo("请输入有效的 11 位中国大陆手机号码。");
    }
}
