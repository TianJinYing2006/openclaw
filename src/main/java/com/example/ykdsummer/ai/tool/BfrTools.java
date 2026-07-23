package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.tianxing.BfrInfo;
import com.example.ykdsummer.tianxing.BfrService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI 暴露给模型的 BFR 体脂率计算工具边界。
 */
@Component
public class BfrTools implements AiTool {

    private final BfrService bfrService;

    public BfrTools(BfrService bfrService) {
        this.bfrService = bfrService;
    }

    @Tool(
            name = "calculate_bfr",
            description = "计算体脂率（BFR），根据身高、体重、年龄、性别估算身体脂肪率和健康等级。"
                    + "用户不提供完整参数时应追问补齐。"
    )
    public String calculateBfr(
            @ToolParam(required = true, description = "年龄，单位：岁")
            int age,
            @ToolParam(required = true, description = "身高，单位：厘米(cm)")
            int height,
            @ToolParam(required = true, description = "体重，单位：千克(kg)")
            int weight,
            @ToolParam(required = true, description = "性别：0=女性，1=男性")
            int sex
    ) {
        BfrInfo result = bfrService.calculateBfr(age, height, weight, sex);
        return result.toString();
    }
}
