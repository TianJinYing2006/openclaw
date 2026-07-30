package com.example.ykdsummer.ai.tool.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 飞书多维表格（Bitable）操作工具。
 *
 * <p>提供查询、新增、更新 Bitable 记录的能力，
 * 供 Spring AI Function Calling 自动调用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuBitableTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuBitableTools.class);

    private final FeishuClient feishuClient;
    private final ObjectMapper objectMapper;

    public FeishuBitableTools(FeishuClient feishuClient, ObjectMapper objectMapper) {
        this.feishuClient = feishuClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "feishu_bitable_query", description = """
            查询飞书多维表格（Bitable）的记录列表。
            需提供多维表格的 app_token（Base Token）和 table_id（数据表 ID）。
            可选分页参数 page_size（默认20）和 page_token（分页标记）。
            适用于：查看飞书多维表格中的数据、筛选记录等场景。
            """)
    public String bitableQuery(
            @ToolParam(required = true, description = "多维表格的 app_token / Base Token，格式如 'bascnNzAxxxxxxxxx'") String appToken,
            @ToolParam(required = true, description = "数据表 ID，格式如 'tblxxxxxxxx'") String tableId,
            @ToolParam(required = false, description = "每页返回的记录数，默认 20，最大 500") Integer pageSize,
            @ToolParam(required = false, description = "分页标记，用于翻页。第一次查询不传，后续传上一页返回的 page_token") String pageToken
    ) {
        log.info("Feishu bitable query: appToken={}, tableId={}, pageSize={}", appToken, tableId, pageSize);
        try {
            String result = feishuClient.listBitableRecords(appToken, tableId, pageSize, pageToken);
            log.info("Feishu bitable query success: resultLength={}", result.length());
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu bitable query failed: {}", e.getMessage());
            return "【查询失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu bitable query error", e);
            return "【查询异常】" + e.getMessage();
        }
    }

    @Tool(name = "feishu_bitable_add_record", description = """
            向飞书多维表格（Bitable）新增一条记录。
            需提供多维表格的 app_token（Base Token）、table_id（数据表 ID）和字段数据。
            字段数据以 JSON 对象格式传入，如 {"字段名1": "值1", "字段名2": "值2"}。
            适用于：向飞书多维表格中添加新数据。
            """)
    public String bitableAddRecord(
            @ToolParam(required = true, description = "多维表格的 app_token / Base Token") String appToken,
            @ToolParam(required = true, description = "数据表 ID") String tableId,
            @ToolParam(required = true, description = """
                    字段数据，JSON 对象格式，键为字段名，值为字段值。
                    如 {"姓名": "张三", "年龄": 25, "日期": 1710000000}
                    日期字段请传 Unix 时间戳（秒）。
                    """) String fieldsJson
    ) {
        log.info("Feishu bitable add record: appToken={}, tableId={}", appToken, tableId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = objectMapper.readValue(fieldsJson, Map.class);
            String result = feishuClient.createBitableRecord(appToken, tableId, fields);
            log.info("Feishu bitable add record success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu bitable add record failed: {}", e.getMessage());
            return "【新增失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu bitable add record error", e);
            return "【新增异常】" + e.getMessage();
        }
    }

    @Tool(name = "feishu_bitable_update_record", description = """
            更新飞书多维表格（Bitable）中已有的一条记录。
            需提供多维表格的 app_token（Base Token）、table_id（数据表 ID）、record_id（记录 ID）和要更新的字段数据。
            适用于：修改飞书多维表格中已有的数据。
            """)
    public String bitableUpdateRecord(
            @ToolParam(required = true, description = "多维表格的 app_token / Base Token") String appToken,
            @ToolParam(required = true, description = "数据表 ID") String tableId,
            @ToolParam(required = true, description = "记录 ID，格式如 'recxxxxxxxx'") String recordId,
            @ToolParam(required = true, description = """
                    要更新的字段数据，JSON 对象格式，键为字段名，值为新字段值。
                    如 {"年龄": 26, "备注": "已更新"}
                    """) String fieldsJson
    ) {
        log.info("Feishu bitable update record: appToken={}, tableId={}, recordId={}",
                appToken, tableId, recordId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = objectMapper.readValue(fieldsJson, Map.class);
            String result = feishuClient.updateBitableRecord(appToken, tableId, recordId, fields);
            log.info("Feishu bitable update record success");
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu bitable update record failed: {}", e.getMessage());
            return "【更新失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu bitable update record error", e);
            return "【更新异常】" + e.getMessage();
        }
    }
}
