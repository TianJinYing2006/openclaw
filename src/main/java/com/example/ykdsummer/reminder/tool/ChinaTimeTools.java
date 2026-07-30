package com.example.ykdsummer.reminder.tool;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.AiTool;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Kept separate from reminder management so scheduled Agents can use time without rescheduling themselves. */
@Component
public class ChinaTimeTools implements AiTool {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private final AiTraceLogger trace;

    public ChinaTimeTools(AiTraceLogger trace) {
        this.trace = trace;
    }

    @Tool(name = "get_current_china_time", description = "当用户使用今天、明天、后天、下周、几点钟等相对时间，"
            + "或任务需要精确中国当前时间时调用。设置定时任务前必须用它把相对时间换算为绝对时间。")
    public String getCurrentChinaTime() {
        String value = ZonedDateTime.now(DISPLAY_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        trace.toolResult("get_current_china_time", value);
        return "中国当前时间：" + value;
    }
}
