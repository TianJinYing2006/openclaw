package com.example.ykdsummer.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Steam 用户查询工具。
 *
 * <p>调用 uapis.cn 的免费 API 查询 Steam 用户公开资料。
 * 支持 SteamID64、自定义 URL 名称、ID3 格式、好友代码等多种标识符。</p>
 */
@Component
public class SteamUserTools {

    private static final Logger log = LoggerFactory.getLogger(SteamUserTools.class);
    private static final String API_URL = "https://uapis.cn/api/v1/game/steam/summary";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SteamUserTools() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(
            name = "get_steam_user",
            description = "查询 Steam 用户的公开资料信息。"
                    + "当用户询问某个 Steam 玩家的信息、查看 Steam 用户资料、查询 SteamID 等话题时调用此工具。"
                    + "支持 SteamID64（纯数字）、自定义 URL 名称、ID3 格式（STEAM_X:Y:Z）、好友代码等多种格式。"
                    + "返回用户昵称、头像、在线状态、真实姓名、个人资料链接等信息。"
    )
    public String getSteamUser(
            @ToolParam(
                    required = true,
                    description = "Steam 用户标识，支持以下格式："
                            + "SteamID64（如 76561197960287930）、"
                            + "自定义 URL 名称（如 gabelogannewell）、"
                            + "ID3 格式（如 STEAM_0:0:22202）、"
                            + "好友代码（如 22202）、"
                            + "完整个人资料链接"
            )
            String steamId
    ) {
        log.info("Steam user request: {}", steamId);

        try {
            // 判断输入格式，选择合适的参数名
            String paramName = detectParamName(steamId);
            String url = API_URL + "?" + paramName + "=" + steamId;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return "未找到该 Steam 用户，请检查输入是否正确";
            }
            if (response.statusCode() == 401) {
                return "Steam API 认证失败，请稍后重试";
            }
            if (response.statusCode() != 200) {
                log.warn("Steam API failed: status={}, body={}", response.statusCode(), response.body());
                return "查询 Steam 用户失败，HTTP 状态码：" + response.statusCode();
            }

            JsonNode root = objectMapper.readTree(response.body());

            // 检查是否是错误响应
            if (root.has("code") && root.has("message")) {
                return "查询失败：" + root.path("message").asText();
            }

            return formatUserInfo(root);

        } catch (IOException | InterruptedException exception) {
            log.warn("Steam user request failed: {}", exception.getMessage());
            return "查询 Steam 用户信息失败，请稍后重试";
        }
    }

    private String detectParamName(String input) {
        String trimmed = input.trim();
        // Steam ID3 格式: STEAM_X:Y:Z
        if (trimmed.toUpperCase().startsWith("STEAM_")) {
            return "id3";
        }
        // 完整的 Steam 个人资料链接
        if (trimmed.contains("steamcommunity.com")) {
            return "steamid";
        }
        // 纯数字 - 可能是 SteamID64 或好友代码
        if (trimmed.matches("\\d+")) {
            // SteamID64 通常是 17 位数字
            if (trimmed.length() >= 17) {
                return "steamid";
            }
            // 较短的数字可能是好友代码
            return "steamid";
        }
        // 其他情况当作自定义 URL 名称
        return "id";
    }

    private String formatUserInfo(JsonNode user) {
        StringBuilder result = new StringBuilder();

        String personaname = user.path("personaname").asText("未知用户");
        String realname = user.path("realname").asText("");
        String steamid = user.path("steamid").asText("");
        String profileurl = user.path("profileurl").asText("");
        int personastate = user.path("personastate").asInt(0);
        int visibility = user.path("communityvisibilitystate").asInt(1);
        String loccountrycode = user.path("loccountrycode").asText("");
        String timecreatedStr = user.path("timecreated_str").asText("");
        String avatarfull = user.path("avatarfull").asText("");

        result.append("👤 **").append(personaname).append("**\n\n");

        if (!realname.isBlank()) {
            result.append("📛 真实姓名：").append(realname).append("\n");
        }

        result.append("🆔 SteamID：").append(steamid).append("\n");
        result.append("🌐 在线状态：").append(getStatusText(personastate)).append("\n");
        result.append("🔒 资料可见性：").append(visibility == 3 ? "公开" : "私密").append("\n");

        if (!loccountrycode.isBlank()) {
            result.append("🌍 地区：").append(loccountrycode).append("\n");
        }

        if (!timecreatedStr.isBlank()) {
            result.append("📅 注册时间：").append(timecreatedStr).append("\n");
        }

        if (!profileurl.isBlank()) {
            result.append("🔗 个人资料：").append(profileurl).append("\n");
        }

        if (!avatarfull.isBlank()) {
            result.append("🖼️ 头像：").append(avatarfull).append("\n");
        }

        return result.toString();
    }

    private String getStatusText(int state) {
        return switch (state) {
            case 0 -> "离线";
            case 1 -> "在线";
            case 2 -> "忙碌";
            case 3 -> "离开";
            case 4 -> "打盹";
            case 5 -> "想交易";
            case 6 -> "想玩";
            default -> "未知";
        };
    }
}
