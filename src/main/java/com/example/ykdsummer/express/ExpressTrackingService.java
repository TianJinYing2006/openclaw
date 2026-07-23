package com.example.ykdsummer.express;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 快递鸟物流查询服务 — 支持查询国内 1500+ 快递公司的物流轨迹。
 *
 * <p>使用快递鸟即时查询 API（RequestType=1002），
 * 端点：https://api.kdniao.com/Ebusiness/EbusinessOrderHandle.aspx</p>
 */
@Service
public class ExpressTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ExpressTrackingService.class);
    private static final String API_URL = "https://api.kdniao.com/Ebusiness/EbusinessOrderHandle.aspx";

    /** 快递公司编码 → 中文名称映射（常用） */
    private static final Map<String, String> SHIPPER_NAMES = Map.ofEntries(
            Map.entry("SF", "顺丰速运"),
            Map.entry("STO", "申通快递"),
            Map.entry("YTO", "圆通速递"),
            Map.entry("ZTO", "中通快递"),
            Map.entry("YT", "韵达快递"),
            Map.entry("JD", "京东物流"),
            Map.entry("EMS", "EMS"),
            Map.entry("HHTT", "天天快递"),
            Map.entry("UC", "优速快递"),
            Map.entry("GTO", "国通快递"),
            Map.entry("DBL", "德邦快递"),
            Map.entry("FAST", "快捷快递"),
            Map.entry("QFKD", "全峰快递"),
            Map.entry("JJKY", "佳吉快运"),
            Map.entry("ANE", "安能物流"),
            Map.entry("YZPY", "中国邮政平邮"),
            Map.entry("ZJS", "宅急送"),
            Map.entry("SURE", "速尔快递"),
            Map.entry("BSKY", "百世快递"),
            Map.entry("YD", "韵达快运"),
            Map.entry("DEPPON", "德邦物流"),
            Map.entry("JTSD", "极兔速递"),
            Map.entry("CROSS-BORDER", "跨境国际"),
            Map.entry("FEDEX", "FedEx"),
            Map.entry("UPS", "UPS"),
            Map.entry("DHL", "DHL"),
            Map.entry("TNT", "TNT")
    );

    private final String eBusinessId;
    private final String appKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ExpressTrackingService(
            @Value("${kdniao.ebusiness-id:}") String eBusinessId,
            @Value("${kdniao.api-key:}") String appKey,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.eBusinessId = eBusinessId;
        this.appKey = appKey;
        this.restClient = restClientBuilder.baseUrl(API_URL).build();
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return eBusinessId != null && !eBusinessId.isBlank() && appKey != null && !appKey.isBlank();
    }

    /**
     * 查询物流轨迹
     *
     * @param logisticCode 快递单号
     * @param shipperCode  快递公司编码（可选，不传则自动识别）
     * @return 物流跟踪信息
     */
    public ExpressTrackingInfo queryTracking(String logisticCode, String shipperCode) {
        if (!isConfigured()) {
            throw new IllegalStateException("快递鸟查询未配置 EBusinessID 或 API Key");
        }

        String normalizedLogistic = normalizeLogisticCode(logisticCode);
        String normalizedShipper = normalizeShipperCode(shipperCode);

        // 构建请求 JSON
        String requestData = buildRequestJson(normalizedShipper, normalizedLogistic);

        // 计算签名：MD5 + Base64（签名用的原始 JSON，不编码）
        String dataSign = sign(requestData);

        // 手动构建 form body：URL 编码每个值，RestClient 不再二次编解码
        String formBody = encodeForm(
                "RequestData", requestData,
                "EBusinessID", eBusinessId,
                "RequestType", "1002",
                "DataSign", dataSign,
                "DataType", "2"
        );

        log.info("Querying express tracking: shipper={}, logistic={}", normalizedShipper, normalizedLogistic);
        log.debug("Request data: {}", requestData);

        // 先以 String 形式读取原始响应，便于调试
        String rawJson = restClient.post()
                .uri(API_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody)
                .retrieve()
                .body(String.class);

        log.debug("Raw API response: {}", rawJson);

        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("快递鸟接口返回空响应");
        }

        // 手动反序列化
        TrackingApiResponse response;
        try {
            response = objectMapper.readValue(rawJson, TrackingApiResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析快递鸟响应失败: " + rawJson, e);
        }

        String shipperName = resolveShipperName(
                response.shipperCode() != null ? response.shipperCode() : normalizedShipper);

        List<ExpressTrackingInfo.Trace> traces = response.traces() != null
                ? response.traces().stream()
                    .map(t -> new ExpressTrackingInfo.Trace(
                            t.acceptTime() != null ? t.acceptTime() : "",
                            t.acceptStation() != null ? t.acceptStation() : "",
                            t.location() != null ? t.location() : ""
                    ))
                    .toList()
                : Collections.emptyList();

        int state;
        try {
            state = response.state() != null ? Integer.parseInt(response.state()) : 0;
        } catch (NumberFormatException e) {
            state = 0;
        }

        return new ExpressTrackingInfo(
                response.shipperCode() != null ? response.shipperCode() : normalizedShipper,
                shipperName,
                response.logisticCode() != null ? response.logisticCode() : normalizedLogistic,
                state,
                response.success(),
                response.reason(),
                traces
        );
    }

    /** 手动拼接 form-urlencoded 请求体 */
    private static String encodeForm(String... keyValues) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keyValues.length; i += 2) {
                if (i > 0) sb.append('&');
                sb.append(keyValues[i]).append('=')
                        .append(URLEncoder.encode(keyValues[i + 1], StandardCharsets.UTF_8.name()));
            }
            return sb.toString();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private String buildRequestJson(String shipperCode, String logisticCode) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"OrderCode\":\"\"");
        if (shipperCode != null && !shipperCode.isBlank()) {
            json.append(",\"ShipperCode\":\"").append(escapeJson(shipperCode)).append("\"");
        }
        json.append(",\"LogisticCode\":\"").append(escapeJson(logisticCode)).append("\"");
        json.append("}");
        return json.toString();
    }

    /** MD5 + Base64 签名 */
    private String sign(String data) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((data + appKey).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeLogisticCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("快递单号不能为空");
        }
        String normalized = code.strip();
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("快递单号过长");
        }
        return normalized;
    }

    private static String normalizeShipperCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String normalized = code.strip().toUpperCase();
        // 中文名称尝试转编码
        for (Map.Entry<String, String> entry : SHIPPER_NAMES.entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }
        return normalized;
    }

    private static String resolveShipperName(String code) {
        if (code == null || code.isBlank()) {
            return "未知快递";
        }
        return SHIPPER_NAMES.getOrDefault(code.toUpperCase(), code);
    }

    /** 快递鸟 API 原始响应 */
    @SuppressWarnings("unused")
    private record TrackingApiResponse(
            @JsonProperty("Success") boolean success,
            @JsonProperty("Reason") String reason,
            @JsonProperty("State") String state,
            @JsonProperty("EBusinessID") String eBusinessId,
            @JsonProperty("ShipperCode") String shipperCode,
            @JsonProperty("LogisticCode") String logisticCode,
            @JsonProperty("Traces") List<TraceApiItem> traces
    ) {}

    private record TraceApiItem(
            @JsonProperty("AcceptTime") String acceptTime,
            @JsonProperty("AcceptStation") String acceptStation,
            @JsonProperty("Location") String location
    ) {}
}
