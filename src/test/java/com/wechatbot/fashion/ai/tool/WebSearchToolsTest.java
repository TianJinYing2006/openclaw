package com.wechatbot.fashion.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class WebSearchToolsTest {

    @Test
    void usesAnonymousVisitorQuotaWhenTheUapisKeyIsMissing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"results":[{"title":"科技新闻","url":"https://example.com/news",
                    "snippet":"今日更新","source":"UAPIs"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RealtimeSearchFallback fallback = mock(RealtimeSearchFallback.class);
            WebSearchTools tools = new WebSearchTools(
                    "",
                    fallback,
                    HttpClient.newHttpClient(),
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/search")
            );

            String result = tools.webSearch("今天的科技新闻");

            assertThat(result).contains("科技新闻", "今日更新", "https://example.com/news");
            assertThat(requestBody.get()).contains("今天的科技新闻");
            assertThat(authorization.get()).isNull();
            verifyNoInteractions(fallback);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBlankQueryBeforeCallingAnyProvider() {
        RealtimeSearchFallback fallback = mock(RealtimeSearchFallback.class);
        String result = new WebSearchTools("", fallback).webSearch("  ");

        assertThat(result).isEqualTo("搜索关键词不能为空");
        verifyNoInteractions(fallback);
    }
}
