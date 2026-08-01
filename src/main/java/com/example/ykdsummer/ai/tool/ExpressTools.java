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

/** Queries the UAPIs public express-tracking endpoint. */
@Component
public class ExpressTools implements AiTool {

    private static final URI API_URL = URI.create("https://uapis.cn/api/v1/misc/tracking/query");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI apiUrl;
    private final String apiKey;

    @Autowired
    public ExpressTools(@Value("${uapis.api-key:}") String apiKey) {
        this(
                apiKey,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                API_URL
        );
    }

    ExpressTools(String apiKey, HttpClient httpClient, URI apiUrl) {
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Tool(
            name = "query_express_tracking",
            description = "查询国内快递的物流轨迹信息，支持顺丰、圆通、申通、中通、韵达、京东、极兔、EMS、德邦等主流快递公司。"
                    + "用户提供单号时可以直接查询，快递公司编码会自动识别；"
                    + "如果自动识别失败或想指定快递公司，可以传 shipperCode 参数；"
                    + "顺丰等需要验证时，可传收件人手机号后四位。"
    )
    public Object queryExpressTracking(
            @ToolParam(
                    required = true,
                    description = "快递运单号，纯数字或数字字母组合，例如 SF1234567890、YT1234567890"
            )
            String logisticCode,

            @ToolParam(
                    required = false,
                    description = "快递公司编码（可选），例如 sf、yto、zto、sto、jd。不传则自动识别。"
            )
            String shipperCode,

            @ToolParam(
                    required = false,
                    description = "收件人手机尾号四位。仅当顺丰等快递返回暂无物流信息并要求验证时提供。"
            )
            String phoneLast4
    ) {
        String trackingNumber = logisticCode == null ? "" : logisticCode.strip();
        if (!trackingNumber.matches("[A-Za-z0-9-]{6,50}")) {
            return "请输入有效的快递单号。";
        }
        String normalizedPhoneLast4 = phoneLast4 == null ? "" : phoneLast4.strip();
        if (!normalizedPhoneLast4.isEmpty() && !normalizedPhoneLast4.matches("\\d{4}")) {
            return "收件人手机号仅需提供四位数字尾号。";
        }

        try {
            StringBuilder query = new StringBuilder("?tracking_number=")
                    .append(encode(trackingNumber));
            if (shipperCode != null && !shipperCode.isBlank()) {
                query.append("&carrier_code=").append(encode(shipperCode.strip()));
            }
            if (!normalizedPhoneLast4.isEmpty()) {
                query.append("&phone=").append(encode(normalizedPhoneLast4));
            }

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl + query.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .GET();
            if (isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey.strip());
            }
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 404) {
                return "未查询到物流轨迹。顺丰等快递可能需要提供收件人手机尾号四位后重试。";
            }
            if (response.statusCode() != 200) {
                return "查询快递物流失败，HTTP 状态码：" + response.statusCode()
                        + "。" + responseMessage(response.body());
            }

            JsonNode result = objectMapper.readTree(response.body());
            if (result.has("code") && result.has("message")) {
                return "查询快递物流失败：" + result.path("message").asText("未知错误");
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "快递查询被中断，请稍后重试。";
        } catch (IOException | IllegalArgumentException exception) {
            return "查询快递物流失败，请稍后重试。";
        }
    }

    private String responseMessage(String responseBody) {
        try {
            return objectMapper.readTree(responseBody).path("message").asText("");
        } catch (IOException exception) {
            return "";
        }
    }

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equalsIgnoreCase(apiKey.strip());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
