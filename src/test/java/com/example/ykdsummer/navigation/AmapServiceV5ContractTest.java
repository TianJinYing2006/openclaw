package com.example.ykdsummer.navigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AmapServiceV5ContractTest {

    @Test
    void usesV5TransitEndpointWithBothCityCodes() throws IOException {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/direction/transit/integrated", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            json(exchange, """
                    {"status":"1","info":"OK","route":{"origin":"120.1,30.2","destination":"120.2,30.3",
                    "transits":[{"distance":"1200","duration":"600"}]}}
                    """);
        });
        server.start();
        try {
            AmapService service = new AmapService(RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build(), "test-key");

            AmapRouteResult result = service.routePlan(
                    "120.1,30.2", "120.2,30.3", "transit", "西湖", "0571", "0571");

            assertThat(result.status()).isEqualTo("1");
            assertThat(result.route().distance()).isEqualTo("1200");
            assertThat(query.get()).contains("city1=0571", "city2=0571", "key=test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsV3GeocodeAndReadsCityCode() throws IOException {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/geocode/geo", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            json(exchange, """
                    {"status":"1","info":"OK","geocodes":[{"formatted_address":"杭州市西湖区","citycode":"0571",
                    "adcode":"330106","location":"120.1,30.2"}]}
                    """);
        });
        server.start();
        try {
            AmapService service = new AmapService(RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build(), "test-key");

            AmapGeoResult result = service.geocode("杭州西湖", "杭州");

            assertThat(result.geocodes()).singleElement().satisfies(geocode -> {
                assertThat(geocode.citycode()).isEqualTo("0571");
                assertThat(geocode.adcode()).isEqualTo("330106");
            });
            assertThat(query.get()).contains("address=%E6%9D%AD%E5%B7%9E%E8%A5%BF%E6%B9%96", "city=%E6%9D%AD%E5%B7%9E");
            assertThat(query.get()).doesNotContain("extensions=");
        } finally {
            server.stop(0);
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
