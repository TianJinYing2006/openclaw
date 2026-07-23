package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 飞书日历操作工具。
 *
 * <p>提供查询和创建日历事件的能力，
 * 供 Spring AI Function Calling 自动调用。</p>
 */
@Component
public class FeishuCalendarTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuCalendarTools.class);

    private final FeishuClient feishuClient;

    public FeishuCalendarTools(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Tool(name = "feishu_calendar_list_events", description = """
            查询飞书日历中的事件列表。
            需提供日历 ID（calendarId）。
            可按时间范围（startTime / endTime，Unix 秒级时间戳）筛选。
            适用于：查看日程安排、检查某时间段是否有冲突等。
            """)
    public String calendarListEvents(
            @ToolParam(required = true, description = "日历 ID，格式如 'feishu.cn_xxxxxxxxxx@group.calendar.feishu.cn' 或 'primary'") String calendarId,
            @ToolParam(required = false, description = "每页返回的事件数，默认 20") Integer pageSize,
            @ToolParam(required = false, description = "分页标记，用于翻页") String pageToken,
            @ToolParam(required = false, description = "时间范围起始（Unix 秒级时间戳），如 '1710000000'") String startTime,
            @ToolParam(required = false, description = "时间范围结束（Unix 秒级时间戳），如 '1710086400'") String endTime
    ) {
        log.info("Feishu calendar list events: calendarId={}, pageSize={}, startTime={}, endTime={}",
                calendarId, pageSize, startTime, endTime);
        try {
            String result = feishuClient.listCalendarEvents(calendarId, pageSize, pageToken, startTime, endTime);
            log.info("Feishu calendar list events success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu calendar list events failed: {}", e.getMessage());
            return "【查询失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu calendar list events error", e);
            return "【查询异常】" + e.getMessage();
        }
    }

    @Tool(name = "feishu_calendar_create_event", description = """
            在飞书日历中创建新事件。
            需提供日历 ID（calendarId）、事件标题（summary）、开始和结束时间。
            可选参数：描述（description）、时区（timezone，默认 Asia/Shanghai）。
            时间传 Unix 秒级时间戳，如 1710000000 表示 2024-03-10 00:00:00。
            适用于：添加日程提醒、安排会议等。
            """)
    public String calendarCreateEvent(
            @ToolParam(required = true, description = "日历 ID，可用 'primary' 表示主日历") String calendarId,
            @ToolParam(required = true, description = "事件标题/摘要，如 '产品评审会议'") String summary,
            @ToolParam(required = false, description = "事件描述，如会议议程等详细信息") String description,
            @ToolParam(required = true, description = "开始时间（Unix 秒级时间戳），如 '1710000000'") String startTime,
            @ToolParam(required = true, description = "结束时间（Unix 秒级时间戳），如 '1710003600'") String endTime,
            @ToolParam(required = false, description = "时区，如 'Asia/Shanghai' 或 'America/New_York'，默认 Asia/Shanghai") String timezone
    ) {
        log.info("Feishu calendar create event: calendarId={}, summary={}", calendarId, summary);
        try {
            String tz = (timezone != null && !timezone.isBlank()) ? timezone : "Asia/Shanghai";
            String result = feishuClient.createCalendarEvent(calendarId, summary, description, startTime, endTime, tz);
            log.info("Feishu calendar create event success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu calendar create event failed: {}", e.getMessage());
            return "【创建失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu calendar create event error", e);
            return "【创建异常】" + e.getMessage();
        }
    }
}
