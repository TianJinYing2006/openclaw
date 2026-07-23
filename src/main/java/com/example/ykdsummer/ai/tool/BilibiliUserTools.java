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
 * B站用户查询工具。
 *
 * <p>调用 uapis.cn 的 API 查询 B站用户公开资料。
 * 返回头像、昵称、等级、签名、粉丝数、视频数等信息。</p>
 */
@Component
public class BilibiliUserTools {

    private static final Logger log = LoggerFactory.getLogger(BilibiliUserTools.class);
    private static final String API_URL = "https://uapis.cn/api/v1/social/bilibili/userinfo";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${uapis.api-key:not-configured}")
    private String apiKey;

    public BilibiliUserTools() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(
            name = "get_bilibili_user",
            description = "查询 B站（Bilibili）用户的公开资料信息。"
                    + "当用户询问某个 B站用户的信息、查看 B站 UP主资料、查询 B站 UID 等话题时调用此工具。"
                    + "返回用户头像、昵称、等级、签名、粉丝数、视频数等信息。"
    )
    public String getBilibiliUser(
            @ToolParam(
                    required = true,
                    description = "B站用户的 UID（纯数字 ID）"
            )
            String uid
    ) {
        log.info("Bilibili user request: {}", uid);

        try {
            String url = API_URL + "?uid=" + uid;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return "未找到该 B站用户，请检查 UID 是否正确";
            }
            if (response.statusCode() == 401) {
                return "API 认证失败，请检查 API Key";
            }
            if (response.statusCode() != 200) {
                log.warn("Bilibili API failed: status={}, body={}", response.statusCode(), response.body());
                return "查询 B站用户失败，HTTP 状态码：" + response.statusCode();
            }

            JsonNode root = objectMapper.readTree(response.body());

            // 检查是否是错误响应
            if (root.has("code") && root.has("message")) {
                return "查询失败：" + root.path("message").asText();
            }

            return formatUserInfo(root);

        } catch (IOException | InterruptedException exception) {
            log.warn("Bilibili user request failed: {}", exception.getMessage());
            return "查询 B站用户信息失败，请稍后重试";
        }
    }

    private String formatUserInfo(JsonNode user) {
        StringBuilder result = new StringBuilder();

        long mid = user.path("mid").asLong(0);
        String name = user.path("name").asText("未知用户");
        String sex = user.path("sex").asText("");
        String face = user.path("face").asText("");
        String sign = user.path("sign").asText("");
        int level = user.path("level").asInt(0);
        String birthday = user.path("birthday").asText("");
        int vipType = user.path("vip_type").asInt(0);
        int vipStatus = user.path("vip_status").asInt(0);
        int following = user.path("following").asInt(0);
        int follower = user.path("follower").asInt(0);
        int archiveCount = user.path("archive_count").asInt(0);
        int articleCount = user.path("article_count").asInt(0);

        result.append("📺 **").append(name).append("**\n\n");

        result.append("🆔 UID：").append(mid).append("\n");

        if (!sex.isBlank() && !"保密".equals(sex)) {
            result.append("性别：").append(sex).append("\n");
        }

        result.append("⭐ 等级：Lv.").append(level).append("\n");

        if (!birthday.isBlank()) {
            result.append("🎂 生日：").append(birthday).append("\n");
        }

        if (!sign.isBlank()) {
            result.append("📝 签名：").append(sign).append("\n");
        }

        // VIP 信息
        if (vipStatus == 1) {
            String vipTypeName = switch (vipType) {
                case 1 -> "大会员";
                case 2 -> "年度大会员";
                default -> "会员";
            };
            result.append("💎 ").append(vipTypeName).append("\n");
        }

        result.append("\n📊 数据统计：\n");
        result.append("   - 关注：").append(formatNumber(following)).append("\n");
        result.append("   - 粉丝：").append(formatNumber(follower)).append("\n");
        result.append("   - 视频：").append(formatNumber(archiveCount)).append("\n");
        if (articleCount > 0) {
            result.append("   - 专栏：").append(formatNumber(articleCount)).append("\n");
        }

        if (!face.isBlank()) {
            result.append("\n🖼️ 头像：").append(face).append("\n");
        }

        result.append("🔗 主页：https://space.bilibili.com/").append(mid).append("\n");

        return result.toString();
    }

    private String formatNumber(int num) {
        if (num >= 10000) {
            return String.format("%.1f万", num / 10000.0);
        }
        return String.valueOf(num);
    }
}
