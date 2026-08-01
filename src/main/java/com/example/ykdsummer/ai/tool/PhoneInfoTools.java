package com.example.ykdsummer.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Queries the public UAPIs mobile-number attribution endpoint. */
@Component
public class PhoneInfoTools {

    private static final URI API_URL = URI.create("https://uapis.cn/api/v1/misc/phoneinfo");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI apiUrl;
    private final String apiKey;

    @Autowired
    public PhoneInfoTools(@Value("${uapis.api-key:}") String apiKey) {
        this(
                apiKey,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                API_URL
        );
    }

    PhoneInfoTools(String apiKey, HttpClient httpClient, URI apiUrl) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Tool(
            name = "get_phone_info",
            description = "查询中国大陆 11 位手机号码的归属省市和运营商。"
                    + "仅在用户明确提供号码并询问归属地或运营商时调用；不能用于推断号码持有人的身份。"
    )
    public String getPhoneInfo(
            @ToolParam(required = true, description = "需要查询的 11 位中国大陆手机号码") String phone
    ) {
        String normalizedPhone = phone == null ? "" : phone.strip();
        if (!normalizedPhone.matches("1\\d{10}")) {
            return "请输入有效的 11 位中国大陆手机号码。";
        }

        try {
            URI requestUri = URI.create(apiUrl + "?phone="
                    + URLEncoder.encode(normalizedPhone, StandardCharsets.UTF_8));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .GET();
            if (isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey.strip());
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 400) {
                return "该手机号码格式不正确或暂时无法查询。";
            }
            if (response.statusCode() != 200) {
                return "查询手机归属地失败，HTTP 状态码：" + response.statusCode();
            }

            JsonNode result = objectMapper.readTree(response.body());
            if (result.has("code") && result.has("message")) {
                return "查询手机归属地失败：" + result.path("message").asText("未知错误");
            }
            String province = result.path("province").asText("").strip();
            String city = result.path("city").asText("").strip();
            String carrier = result.path("sp").asText("").strip();
            if (province.isBlank() && city.isBlank() && carrier.isBlank()) {
                return "未查询到该手机号码的归属地信息。";
            }

            StringBuilder reply = new StringBuilder("手机号码归属地：");
            if (!province.isBlank()) {
                reply.append(province);
            }
            if (!city.isBlank() && !city.equals(province)) {
                reply.append(city);
            }
            if (!carrier.isBlank()) {
                reply.append("，运营商：").append(carrier);
            }
            return reply.toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "手机归属地查询被中断，请稍后重试。";
        } catch (IOException | IllegalArgumentException exception) {
            return "查询手机归属地失败，请稍后重试。";
        }
    }

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equalsIgnoreCase(apiKey.strip());
    }
}
