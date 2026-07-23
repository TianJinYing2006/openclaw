package com.example.ykdsummer.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Epic Games 免费游戏查询工具。
 *
 * <p>调用 uapis.cn 的免费 API 查询 Epic 商店当前和即将推出的免费游戏。
 * AI 会在用户询问 Epic 免费游戏时自动调用此工具。</p>
 */
@Component
public class EpicFreeGamesTools {

    private static final Logger log = LoggerFactory.getLogger(EpicFreeGamesTools.class);
    private static final String API_URL = "https://uapis.cn/api/v1/game/epic-free";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EpicFreeGamesTools() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Tool(
            name = "get_epic_free_games",
            description = "查询 Epic Games 商店当前免费游戏和即将免费的游戏。"
                    + "当用户询问 Epic 免费游戏、Epic 送什么游戏、白嫖游戏等话题时调用此工具。"
                    + "返回当前免费和即将免费的游戏列表，包括游戏名称、原价、免费时间和领取链接。"
    )
    public String getEpicFreeGames() {
        log.info("Epic free games request");

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "YKD-Summer-Bot/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Epic free games API failed: status={}", response.statusCode());
                return "获取 Epic 免费游戏信息失败，HTTP 状态码：" + response.statusCode();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");

            if (!data.isArray() || data.isEmpty()) {
                return "当前没有 Epic 免费游戏信息";
            }

            StringBuilder result = new StringBuilder("🎮 Epic Games 本周免费游戏：\n\n");
            int freeCount = 0;
            int upcomingCount = 0;

            for (JsonNode game : data) {
                boolean isFreeNow = game.path("is_free_now").asBoolean(false);
                String title = game.path("title").asText("未知游戏");
                String originalPrice = game.path("original_price_desc").asText("未知价格");
                String description = game.path("description").asText("");
                String freeStart = game.path("free_start").asText("");
                String freeEnd = game.path("free_end").asText("");
                String link = game.path("link").asText("");

                if (isFreeNow) {
                    freeCount++;
                    result.append("🆓 **").append(title).append("**\n");
                    result.append("   原价：").append(originalPrice).append(" → 现在免费！\n");
                    if (!description.isBlank()) {
                        // 截取前100个字符作为简介
                        String shortDesc = description.length() > 100
                                ? description.substring(0, 100) + "..."
                                : description;
                        result.append("   简介：").append(shortDesc).append("\n");
                    }
                    if (!freeEnd.isBlank()) {
                        result.append("   免费截止：").append(freeEnd).append("\n");
                    }
                    if (!link.isBlank()) {
                        result.append("   领取链接：").append(link).append("\n");
                    }
                    result.append("\n");
                } else {
                    upcomingCount++;
                    result.append("📅 即将免费：**").append(title).append("**\n");
                    result.append("   原价：").append(originalPrice).append("\n");
                    if (!freeStart.isBlank()) {
                        result.append("   免费开始：").append(freeStart).append("\n");
                    }
                    if (!freeEnd.isBlank()) {
                        result.append("   免费截止：").append(freeEnd).append("\n");
                    }
                    result.append("\n");
                }
            }

            if (freeCount == 0 && upcomingCount > 0) {
                result.insert("🎮 Epic Games 本周免费游戏：\n\n".length(),
                        "当前没有免费游戏，但有即将免费的游戏：\n\n");
            }

            result.append("💡 提示：免费游戏每周四更新，记得及时领取！");

            log.info("Epic free games completed, free={}, upcoming={}", freeCount, upcomingCount);
            return result.toString();

        } catch (IOException | InterruptedException exception) {
            log.warn("Epic free games request failed: {}", exception.getMessage());
            return "获取 Epic 免费游戏信息失败，请稍后重试";
        }
    }
}
