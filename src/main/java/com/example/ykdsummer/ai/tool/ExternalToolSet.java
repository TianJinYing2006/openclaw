package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.tool.feishu.FeishuApprovalTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuBitableTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuCalendarTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDocTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuDriveTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuMessageTools;
import com.example.ykdsummer.ai.tool.feishu.FeishuUserTools;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 汇总不属于微信本地资产生命周期的外部业务 Tool。
 * 图片、语音和本地文档仍由主网关显式注册，避免同名旧实现覆盖已验证的回传链路。
 * 飞书 Tool 默认下线，只有 app.feishu.tools.enabled=true 时才会加入本集合。
 */
@Component
public class ExternalToolSet {

    private final Object[] toolBeans;

    public ExternalToolSet(
            BochaWebSearchTools bochaWebSearchTools,
            ObjectProvider<FeishuDocTools> feishuDocTools,
            ObjectProvider<FeishuDriveTools> feishuDriveTools,
            ObjectProvider<FeishuMessageTools> feishuMessageTools,
            ObjectProvider<FeishuUserTools> feishuUserTools,
            ObjectProvider<FeishuCalendarTools> feishuCalendarTools,
            ObjectProvider<FeishuBitableTools> feishuBitableTools,
            ObjectProvider<FeishuApprovalTools> feishuApprovalTools
    ) {
        List<Object> tools = new ArrayList<>();
        tools.add(bochaWebSearchTools);
        addIfPresent(tools, feishuDocTools);
        addIfPresent(tools, feishuDriveTools);
        addIfPresent(tools, feishuMessageTools);
        addIfPresent(tools, feishuUserTools);
        addIfPresent(tools, feishuCalendarTools);
        addIfPresent(tools, feishuBitableTools);
        addIfPresent(tools, feishuApprovalTools);
        this.toolBeans = tools.toArray();
    }

    public Object[] toolBeans() {
        return toolBeans.clone();
    }

    private static void addIfPresent(List<Object> tools, ObjectProvider<?> provider) {
        Object tool = provider.getIfAvailable();
        if (tool != null) tools.add(tool);
    }
}
