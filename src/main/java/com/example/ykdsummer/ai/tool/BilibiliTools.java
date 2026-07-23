package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.bilibili.BilibiliLiveService;
import com.example.ykdsummer.bilibili.LiveRoomInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的 B 站工具边界。业务查询仍由 BilibiliLiveService 完成。
 */
@Component
public class BilibiliTools implements AiTool {

    private final BilibiliLiveService bilibiliLiveService;

    public BilibiliTools(BilibiliLiveService bilibiliLiveService) {
        this.bilibiliLiveService = bilibiliLiveService;
    }

    @Tool(
            name = "get_bilibili_live_room_info",
            description = "查询 B 站直播间的当前状态、标题、在线人数、分区等信息。"
                    + "房间号是数字（长号或短号均可，例如 6、7734200），不要用 UID 或个人主页地址。"
                    + "房间号不明确时应先追问，不能猜测。"
    )
    public LiveRoomInfo getLiveRoomInfo(
            @ToolParam(
                    required = true,
                    description = "B 站直播间号（纯数字），例如 6 或 22637261，不要带 URL 或文字说明。"
            )
            String roomId
    ) {
        return bilibiliLiveService.getLiveRoomInfo(roomId);
    }
}
