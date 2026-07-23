package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.tool.feishu.FeishuApprovalTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuBitableTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuCalendarTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDocTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDriveTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuMessageTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuUserTools;
import org.springframework.stereotype.Component;

/**
 * 汇总不属于微信本地资产生命周期的外部业务 Tool。
 * 图片、语音和本地文档仍由主网关显式注册，避免同名旧实现覆盖已验证的回传链路。
 */
@Component
public class ExternalToolSet {

    private final Object[] toolBeans;

    public ExternalToolSet(
            BochaWebSearchTools bochaWebSearchTools,
            FeishuDocTools feishuDocTools,
            FeishuDriveTools feishuDriveTools,
            FeishuMessageTools feishuMessageTools,
            FeishuUserTools feishuUserTools,
            FeishuCalendarTools feishuCalendarTools,
            FeishuBitableTools feishuBitableTools,
            FeishuApprovalTools feishuApprovalTools
    ) {
        this.toolBeans = new Object[]{
                bochaWebSearchTools,
                feishuDocTools,
                feishuDriveTools,
                feishuMessageTools,
                feishuUserTools,
                feishuCalendarTools,
                feishuBitableTools,
                feishuApprovalTools
        };
    }

    public Object[] toolBeans() {
        return toolBeans.clone();
    }
}
