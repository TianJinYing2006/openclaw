package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.navigation.AmapGeoResult;
import com.example.ykdsummer.navigation.AmapRouteResult;
import com.example.ykdsummer.navigation.AmapService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 高德地图导航工具。提供地理编码和路线规划能力。
 *
 * <p>使用流程：先调用 geoEncode 获取坐标，再调用 routePlan 获取路线方案。</p>
 */
@Component
public class AmapTools {

    private final AmapService amapService;

    public AmapTools(AmapService amapService) {
        this.amapService = amapService;
    }

    @Tool(
            name = "geoEncode",
            description = "将文本地址转换为经纬度坐标。"
                    + "用于路线规划前获取起点和终点的精确坐标。"
                    + "地址存在重名歧义时，应主动向用户确认所在城市。"
                    + "不要向用户展示坐标数值，仅用于内部路线规划。"
    )
    public AmapGeoResult geoEncode(
            @ToolParam(
                    required = true,
                    description = "要转换的地址文本，如'杭州市西湖区'、'北京天安门'"
            )
            String address,
            @ToolParam(
                    required = false,
                    description = "可选，城市名称，用于提高地址解析精度，如'杭州'"
            )
            String city
    ) {
        return amapService.geocode(address, city);
    }

    @Tool(
            name = "routePlan",
            description = "根据起点和终点坐标规划出行路线。"
                    + "必须先调用 geoEncode 获取坐标后再调用此工具。"
                    + "返回驾车、公交、步行等多种交通方式的路线方案，包含距离、耗时和导航链接。"
                    + "默认返回驾车路线，用户指定其他方式时切换对应模式。"
                    + "整理回答时务必展示导航链接，用户点击可直接唤起高德地图导航。"
    )
    public String routePlan(
            @ToolParam(
                    required = true,
                    description = "起点坐标，格式为'经度,纬度'，从 geoEncode 获取"
            )
            String origin,
            @ToolParam(
                    required = true,
                    description = "终点坐标，格式为'经度,纬度'，从 geoEncode 获取"
            )
            String destination,
            @ToolParam(
                    required = false,
                    description = "出行方式：driving（驾车，默认）、walking（步行）、riding（骑行）、transit（公交）"
            )
            String mode,
            @ToolParam(
                    required = true,
                    description = "终点名称，用于生成导航链接，如'西湖'、'北京天安门'"
            )
            String destName
    ) {
        String effectiveMode = (mode == null || mode.isBlank()) ? "driving" : mode.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("从起点到").append(destName).append("的路线规划：\n\n");

        // 查询驾车路线
        AmapRouteResult drivingResult = amapService.routePlan(origin, destination, "driving", destName);
        if (drivingResult.route() != null) {
            sb.append("🚗 驾车：\n");
            sb.append("距离：").append(formatDistance(drivingResult.route().distance())).append("\n");
            sb.append("耗时：").append(formatDuration(drivingResult.route().duration())).append("\n");
            sb.append("导航链接：").append(drivingResult.navigationLink()).append("\n\n");
        }

        // 查询公交路线
        AmapRouteResult transitResult = amapService.routePlan(origin, destination, "transit", destName);
        if (transitResult.route() != null) {
            sb.append("🚇 公交/地铁：\n");
            sb.append("距离：").append(formatDistance(transitResult.route().distance())).append("\n");
            sb.append("耗时：").append(formatDuration(transitResult.route().duration())).append("\n");
            sb.append("导航链接：").append(transitResult.navigationLink()).append("\n\n");
        }

        // 查询步行路线
        AmapRouteResult walkingResult = amapService.routePlan(origin, destination, "walking", destName);
        if (walkingResult.route() != null) {
            sb.append("🚶 步行：\n");
            sb.append("距离：").append(formatDistance(walkingResult.route().distance())).append("\n");
            sb.append("耗时：").append(formatDuration(walkingResult.route().duration())).append("\n");
            sb.append("导航链接：").append(walkingResult.navigationLink()).append("\n\n");
        }

        sb.append("点击链接可直接唤起高德地图导航");
        return sb.toString();
    }

    private String formatDistance(String distance) {
        try {
            long meters = Long.parseLong(distance);
            if (meters >= 1000) {
                return String.format("%.1f公里", meters / 1000.0);
            }
            return meters + "米";
        } catch (NumberFormatException e) {
            return distance;
        }
    }

    private String formatDuration(String duration) {
        try {
            long seconds = Long.parseLong(duration);
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            if (hours > 0) {
                return String.format("%d小时%d分钟", hours, minutes);
            }
            return minutes + "分钟";
        } catch (NumberFormatException e) {
            return duration;
        }
    }
}
