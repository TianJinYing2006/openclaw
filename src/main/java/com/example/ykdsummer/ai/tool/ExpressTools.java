package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.express.ExpressTrackingInfo;
import com.example.ykdsummer.express.ExpressTrackingService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的快递查询工具边界。业务查询仍由 ExpressTrackingService 完成。
 */
@Component
public class ExpressTools implements AiTool {

    private final ExpressTrackingService expressTrackingService;

    public ExpressTools(ExpressTrackingService expressTrackingService) {
        this.expressTrackingService = expressTrackingService;
    }

    @Tool(
            name = "query_express_tracking",
            description = "查询国内快递的物流轨迹信息，支持顺丰、圆通、申通、中通、韵达、京东、极兔等 1500+ 家快递公司。"
                    + "用户提供单号时可以直接查询，快递公司编码会自动识别；"
                    + "如果自动识别失败或想指定快递公司，可以传 shipperCode 参数（如 SF、YTO、ZTO、STO、JD 等）。"
    )
    public ExpressTrackingInfo queryExpressTracking(
            @ToolParam(
                    required = true,
                    description = "快递运单号，纯数字或数字字母组合，例如 SF1234567890、YT1234567890"
            )
            String logisticCode,

            @ToolParam(
                    required = false,
                    description = "快递公司编码（可选），例如 SF=顺丰、YTO=圆通、ZTO=中通、STO=申通、YT=韵达、JD=京东、JTSD=极兔。"
                            + "不传则自动识别快递公司。也可以传中文名称如'顺丰'、'圆通'。"
            )
            String shipperCode
    ) {
        return expressTrackingService.queryTracking(logisticCode, shipperCode);
    }
}
