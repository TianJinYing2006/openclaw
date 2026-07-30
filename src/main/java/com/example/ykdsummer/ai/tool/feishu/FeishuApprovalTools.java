package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 飞书审批操作工具。
 *
 * <p>提供查询审批实例、审批通过、审批拒绝的能力，
 * 供 Spring AI Function Calling 自动调用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuApprovalTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuApprovalTools.class);

    private final FeishuClient feishuClient;

    public FeishuApprovalTools(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Tool(name = "feishu_approval_list", description = """
            查询飞书审批实例列表。
            可按审批定义（approvalCode）和实例状态（instanceStatus）筛选。
            状态值：PENDING（审批中）、APPROVED（已通过）、REJECTED（已拒绝）、CANCELED（已撤销）等。
            适用于：查看待审批列表、查询历史审批记录等。
            """)
    public String approvalList(
            @ToolParam(required = false, description = "审批定义 Code，不传则查所有审批类型的实例") String approvalCode,
            @ToolParam(required = false, description = "实例状态筛选：PENDING / APPROVED / REJECTED / CANCELED / DELETED，不传查所有状态") String instanceStatus,
            @ToolParam(required = false, description = "每页条数，默认 20") Integer pageSize,
            @ToolParam(required = false, description = "分页标记，用于翻页") String pageToken
    ) {
        log.info("Feishu approval list: approvalCode={}, status={}, pageSize={}",
                approvalCode, instanceStatus, pageSize);
        try {
            String result = feishuClient.queryApprovalInstances(approvalCode, instanceStatus, pageSize, pageToken);
            log.info("Feishu approval list success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu approval list failed: {}", e.getMessage());
            return "【查询失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu approval list error", e);
            return "【查询异常】" + e.getMessage();
        }
    }

    @Tool(name = "feishu_approval_approve", description = """
            审批通过一个飞书审批任务。
            需提供审批定义 Code（approvalCode）、审批实例 Code（instanceCode）、任务 ID（taskId）和操作用户 ID（userId）。
            可选提供审批意见（comment）。
            适用于：审批通过请假、报销、采购等申请。
            """)
    public String approvalApprove(
            @ToolParam(required = true, description = "审批定义 Code，如 '7C468A54-5B72-41D3-8712-204B35C3A2B3'") String approvalCode,
            @ToolParam(required = true, description = "审批实例 Code") String instanceCode,
            @ToolParam(required = true, description = "任务 ID") String taskId,
            @ToolParam(required = true, description = "操作用户 ID（user_id）") String userId,
            @ToolParam(required = false, description = "审批意见，如 '同意，请继续'") String comment
    ) {
        log.info("Feishu approval approve: approvalCode={}, instanceCode={}, taskId={}, userId={}",
                approvalCode, instanceCode, taskId, userId);
        try {
            String result = feishuClient.approveTask(approvalCode, instanceCode, taskId, userId, comment);
            log.info("Feishu approval approve success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu approval approve failed: {}", e.getMessage());
            return "【审批失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu approval approve error", e);
            return "【审批异常】" + e.getMessage();
        }
    }

    @Tool(name = "feishu_approval_reject", description = """
            拒绝/驳回一个飞书审批任务。
            需提供审批定义 Code（approvalCode）、审批实例 Code（instanceCode）、任务 ID（taskId）和操作用户 ID（userId）。
            建议提供拒绝原因（comment）。
            适用于：驳回请假、报销、采购等申请。
            """)
    public String approvalReject(
            @ToolParam(required = true, description = "审批定义 Code") String approvalCode,
            @ToolParam(required = true, description = "审批实例 Code") String instanceCode,
            @ToolParam(required = true, description = "任务 ID") String taskId,
            @ToolParam(required = true, description = "操作用户 ID（user_id）") String userId,
            @ToolParam(required = false, description = "拒绝原因，如 '材料不齐全，请补充'") String comment
    ) {
        log.info("Feishu approval reject: approvalCode={}, instanceCode={}, taskId={}, userId={}",
                approvalCode, instanceCode, taskId, userId);
        try {
            String result = feishuClient.rejectTask(approvalCode, instanceCode, taskId, userId, comment);
            log.info("Feishu approval reject success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu approval reject failed: {}", e.getMessage());
            return "【驳回失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu approval reject error", e);
            return "【驳回异常】" + e.getMessage();
        }
    }
}
