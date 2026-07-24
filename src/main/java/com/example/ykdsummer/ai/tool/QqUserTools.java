package com.example.ykdsummer.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * QQ 用户查询工具。
 *
 * <p>调用 uapis.cn 的 API 查询 QQ 用户公开资料。
 * 返回头像、昵称、个性签名、等级、VIP状态等信息。</p>
 */
@Component
public class QqUserTools {

    private static final Logger log = LoggerFactory.getLogger(QqUserTools.class);
    private static final String API_URL = "https://uapis.cn/api/v1/social/qq/userinfo";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${uapis.api-key:}")
    private String apiKey;

    public QqUserTools() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(
            name = "get_qq_user",
            description = "查询 QQ 用户的公开资料信息。"
                    + "当用户询问某个 QQ 号的信息、查看 QQ 用户资料、查询 QQ 等话题时调用此工具。"
                    + "返回用户头像、昵称、个性签名、等级、VIP状态、注册时间等信息。"
    )
    public String getQqUser(
            @ToolParam(
                    required = true,
                    description = "要查询的 QQ 号码"
            )
            String qqNumber
    ) {
        log.info("QQ user request: {}", qqNumber);

        try {
            String url = API_URL + "?qq=" + qqNumber;

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .GET();
            if (isApiKeyConfigured()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey.strip());
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return "未找到该 QQ 用户，请检查 QQ 号是否正确";
            }
            if (response.statusCode() == 401) {
                return "API 认证失败，请检查 API Key";
            }
            if (response.statusCode() != 200) {
                log.warn("QQ API failed: status={}, body={}", response.statusCode(), response.body());
                return "查询 QQ 用户失败，HTTP 状态码：" + response.statusCode();
            }

            JsonNode root = objectMapper.readTree(response.body());

            // 检查是否是错误响应
            if (root.has("code") && root.has("message")) {
                return "查询失败：" + root.path("message").asText();
            }

            return formatUserInfo(root);

        } catch (IOException | InterruptedException exception) {
            log.warn("QQ user request failed: {}", exception.getMessage());
            return "查询 QQ 用户信息失败，请稍后重试";
        }
    }

    private String formatUserInfo(JsonNode user) {
        StringBuilder result = new StringBuilder();

        String qq = user.path("qq").asText("");
        String nickname = user.path("nickname").asText("未知用户");
        String longNick = user.path("long_nick").asText("");
        String avatarUrl = user.path("avatar_url").asText("");
        int age = user.path("age").asInt(0);
        String sex = user.path("sex").asText("");
        String qid = user.path("qid").asText("");
        JsonNode qqLevelNode = user.path("qq_level");
        String qqLevel = qqLevelNode.isNull() ? "隐藏" : qqLevelNode.asText("未知");
        String location = user.path("location").asText("");
        String email = user.path("email").asText("");
        String regTime = user.path("reg_time").asText("");

        // VIP 状态
        boolean isVip = user.path("is_vip").asBoolean(false);
        boolean isYearsVip = user.path("is_years_vip").asBoolean(false);
        boolean isSvip = user.path("is_svip").asBoolean(false);
        boolean isBigClub = user.path("is_big_club").asBoolean(false);
        int vipLevel = user.path("vip_level").asInt(0);

        result.append("👤 **").append(nickname).append("**\n\n");

        result.append("🆔 QQ号：").append(qq).append("\n");

        if (!sex.isBlank() && !"未知".equals(sex)) {
            result.append("性别：").append(sex).append("\n");
        }

        if (age > 0) {
            result.append("年龄：").append(age).append("\n");
        }

        if (!longNick.isBlank()) {
            result.append("📝 个性签名：").append(longNick).append("\n");
        }

        result.append("⭐ QQ等级：").append(qqLevel).append("\n");

        if (!location.isBlank()) {
            result.append("📍 地区：").append(location).append("\n");
        }

        if (!qid.isBlank()) {
            result.append("🔗 个性域名：").append(qid).append("\n");
        }

        // VIP 信息
        if (isVip || isSvip || isBigClub) {
            result.append("\n💎 会员状态：\n");
            if (isSvip) {
                result.append("   - 超级会员 SVIP（等级 ").append(vipLevel).append("）\n");
            } else if (isVip) {
                result.append("   - 会员 VIP（等级 ").append(vipLevel).append("）\n");
            }
            if (isYearsVip) {
                result.append("   - 年费会员\n");
            }
            if (isBigClub) {
                int bigClubLevel = user.path("big_club_level").asInt(0);
                result.append("   - QQ大会员（等级 ").append(bigClubLevel).append("）\n");
            }
        }

        if (!regTime.isBlank()) {
            result.append("\n📅 注册时间：").append(regTime.substring(0, Math.min(10, regTime.length()))).append("\n");
        }

        if (!avatarUrl.isBlank()) {
            result.append("🖼️ 头像：").append(avatarUrl).append("\n");
        }

        return result.toString();
    }

    private boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equalsIgnoreCase(apiKey.strip());
    }
}
