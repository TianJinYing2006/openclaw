package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.TodayInHistoryInfo;
import com.example.ykdsummer.tianxing.TodayInHistoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的历史事件查询工具边界。
 */
@Component
public class TodayInHistoryTools implements AiTool {

    private final TodayInHistoryService todayInHistoryService;

    public TodayInHistoryTools(TodayInHistoryService todayInHistoryService) {
        this.todayInHistoryService = todayInHistoryService;
    }

    @Tool(
            name = "query_today_in_history",
            description = "查询历史上的今天发生的重要事件。"
                    + "不传参数时默认查今天，也可以指定月日查询。"
    )
    public String queryTodayInHistory(
            @ToolParam(required = false, description = "月份，1-12，不传则查今天")
            Integer month,
            @ToolParam(required = false, description = "日期，1-31，不传则查今天")
            Integer day
    ) {
        java.util.List<TodayInHistoryInfo> events;
        String label;

        if (month != null && day != null) {
            events = todayInHistoryService.getEventsByDate(month, day);
            label = month + "月" + day + "日历史上的今天";
        } else {
            events = todayInHistoryService.getTodayEvents();
            label = "今天历史上的今天";
        }

        return TodayInHistoryInfo.formatList(label, events);
    }
}
