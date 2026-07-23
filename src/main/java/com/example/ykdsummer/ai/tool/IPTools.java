package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.iplocation.IPLocationInfo;
import com.example.ykdsummer.iplocation.IPLocationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的 IP 地理位置查询工具边界。
 */
@Component
public class IPTools implements AiTool {

    private final IPLocationService ipLocationService;

    public IPTools(IPLocationService ipLocationService) {
        this.ipLocationService = ipLocationService;
    }

    @Tool(
            name = "query_ip_location",
            description = "查询指定 IP 地址的地理位置信息，包括国家、省份、城市、ISP 运营商、坐标、时区等。"
                    + "不传 IP 则查询当前访问者的 IP 位置。"
                    + "注意：该工具不支持 HTTPS，请使用 HTTP。"
    )
    public IPLocationInfo queryIPLocation(
            @ToolParam(
                    required = false,
                    description = "IPv4 或 IPv6 地址，例如 8.8.8.8。不传则查询当前 IP。"
            )
            String ip
    ) {
        return ipLocationService.queryLocation(ip);
    }
}
