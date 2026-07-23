package com.example.ykdsummer.bilibili;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * B 站直播间查询服务，调用 uapis.cn API 获取直播状态、在线人数、标题等信息。
 */
@Service
public class BilibiliLiveService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliLiveService.class);
    private static final String BASE_URL = "https://uapis.cn";

    private final RestClient restClient;

    public BilibiliLiveService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
    }

    /**
     * 查询直播间信息
     *
     * @param roomId 直播间号（长号或短号均可）
     * @return 直播间信息
     */
    public LiveRoomInfo getLiveRoomInfo(String roomId) {
        String normalized = normalizeRoomId(roomId);
        LiveRoomApiResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/social/bilibili/liveroom")
                        .queryParam("room_id", normalized)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(LiveRoomApiResponse.class);

        if (response == null || response.roomId() <= 0) {
            throw new IllegalStateException("B 站直播间查询没有返回有效数据，请检查房间号是否正确");
        }
        return new LiveRoomInfo(
                response.uid(),
                response.roomId(),
                response.shortId(),
                response.attention(),
                response.online(),
                response.isPortrait(),
                response.liveStatus(),
                response.title(),
                response.areaName(),
                response.parentAreaName(),
                response.description(),
                response.tags(),
                response.liveTime(),
                response.hotWords()
        );
    }

    private static String normalizeRoomId(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("房间号不能为空");
        }
        String normalized = roomId.strip();
        // 只允许数字
        if (!normalized.matches("\\d+")) {
            throw new IllegalArgumentException("房间号必须为数字");
        }
        // 限制长度，防止恶意输入
        if (normalized.length() > 20) {
            throw new IllegalArgumentException("房间号格式不正确");
        }
        // 去掉前导零
        return normalized.replaceFirst("^0+", "");
    }

    private record LiveRoomApiResponse(
            long uid,
            @JsonProperty("room_id") long roomId,
            @JsonProperty("short_id") long shortId,
            long attention,
            long online,
            @JsonProperty("is_portrait") boolean isPortrait,
            @JsonProperty("live_status") int liveStatus,
            String title,
            @JsonProperty("area_name") String areaName,
            @JsonProperty("parent_area_name") String parentAreaName,
            String description,
            String tags,
            @JsonProperty("live_time") String liveTime,
            @JsonProperty("hot_words") List<String> hotWords
    ) {
    }
}
