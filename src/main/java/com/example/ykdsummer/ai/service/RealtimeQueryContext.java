package com.example.ykdsummer.ai.service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds a controlled time-awareness hint for the model without pre-running a search. */
final class RealtimeQueryContext {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm");
    private static final List<String> TEMPORAL_SIGNALS = List.of(
            "最新", "实时", "当前", "现在", "今天", "今日", "明天", "昨天", "今年",
            "本届", "这一届", "本赛季", "这一轮", "最近", "刚刚", "截至", "截止",
            "正在", "上周", "本周", "下周", "本月", "下个月"
    );

    private RealtimeQueryContext() {
    }

    static String systemContext(String userPrompt) {
        return systemContext(userPrompt, Clock.system(BEIJING));
    }

    static String systemContext(String userPrompt, Clock clock) {
        String timestamp = TIME_FORMAT.format(ZonedDateTime.now(clock).withZoneSameInstant(BEIJING));
        StringBuilder context = new StringBuilder("\n当前北京时间：")
                .append(timestamp)
                .append("。回答涉及日期、赛事进程或当前状态的问题时，以此时间为准，不要把训练数据中的旧日期当作当前时间。");

        Set<String> signals = findTemporalSignals(userPrompt);
        if (!signals.isEmpty()) {
            context.append("本轮包含时效表达：")
                    .append(String.join("、", signals))
                    .append("。这不是强制调用：仍由你判断；但若答案会随日期或事件进展改变，"
                            + "或无法依据已验证信息确定，应优先调用 web_search 核实，不能直接用旧知识补全。");
        }
        return context.toString();
    }

    private static Set<String> findTemporalSignals(String prompt) {
        String value = prompt == null ? "" : prompt;
        Set<String> signals = new LinkedHashSet<>();
        for (String signal : TEMPORAL_SIGNALS) {
            if (value.contains(signal)) {
                signals.add(signal);
            }
        }
        return signals;
    }
}
