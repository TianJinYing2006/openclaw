package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.ScenicSpotInfo;
import com.example.ykdsummer.tianxing.ScenicSpotService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 暴露给模型的旅游景区查询工具边界。
 */
@Component
public class ScenicSpotTools implements AiTool {

    private final ScenicSpotService scenicSpotService;

    public ScenicSpotTools(ScenicSpotService scenicSpotService) {
        this.scenicSpotService = scenicSpotService;
    }

    @Tool(
            name = "search_scenic_spot",
            description = "搜索旅游景区景点，按景点名称或城市查询。"
                    + "例如：\"杭州西湖\"、\"苏州园林\"、\"北京有什么景点\"。"
                    + "返回景点名称、所在地和简介。"
    )
    public String searchScenicSpot(
            @ToolParam(required = true, description = "景点名称或城市名称，例如：西湖、故宫、杭州、北京")
            String query,
            @ToolParam(required = false, description = "是否为按城市搜索，true=按城市搜，false=按景点名称搜，默认false")
            Boolean byCity,
            @ToolParam(required = false, description = "返回数量，默认5，最大15")
            Integer num
    ) {
        int count = (num != null) ? num : 5;
        boolean isCitySearch = (byCity != null && byCity);

        List<ScenicSpotInfo> results;
        if (isCitySearch) {
            results = scenicSpotService.searchByCity(query, count);
        } else {
            results = scenicSpotService.searchByKeyword(query, count);
        }

        return ScenicSpotInfo.formatList(results);
    }
}
