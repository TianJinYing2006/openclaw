package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.HoroscopeInfo;
import com.example.ykdsummer.tianxing.HoroscopeService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的星座运势工具边界。
 */
@Component
public class HoroscopeTools implements AiTool {

    private final HoroscopeService horoscopeService;

    public HoroscopeTools(HoroscopeService horoscopeService) {
        this.horoscopeService = horoscopeService;
    }

    @Tool(
            name = "get_horoscope",
            description = "查询指定星座的今日运势。支持中文名（白羊座/金牛座/双子座/巨蟹座/狮子座/处女座/天秤座/天蝎座/射手座/摩羯座/水瓶座/双鱼座）"
                    + "或英文名（aries/taurus/gemini/cancer/leo/virgo/libra/scorpio/sagittarius/capricorn/aquarius/pisces）。"
                    + "可指定日期 yyyy-MM-dd 格式，不填则默认为今天。"
    )
    public Object getHoroscope(
            @ToolParam(
                    required = true,
                    description = "星座中文名（如白羊座）或英文名（如aries）"
            )
            String astro,
            @ToolParam(
                    required = false,
                    description = "日期，格式 yyyy-MM-dd，不填默认今天"
            )
            String date
    ) {
        if (!horoscopeService.isConfigured()) {
            return InformationToolSupport.unavailable("星座运势服务");
        }
        try {
            return horoscopeService.queryHoroscope(astro, date);
        } catch (RuntimeException exception) {
            return InformationToolSupport.failed("星座运势查询", exception);
        }
    }
}
