package com.example.ykdsummer.ai.tool.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.service.bitable.v1.model.*;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReq;
import com.lark.oapi.service.docx.v1.model.CreateDocumentReqBody;
import com.lark.oapi.service.docx.v1.model.CreateDocumentResp;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentReq;
import com.lark.oapi.service.docx.v1.model.RawContentDocumentResp;
import com.lark.oapi.service.drive.v1.model.Owner;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberReq;
import com.lark.oapi.service.drive.v1.model.TransferOwnerPermissionMemberResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书开放平台 SDK 客户端封装。
 *
 * <p>统一管理 Token 获取/缓存（SDK 自动完成），
 * 提供文档和消息相关的便捷方法。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu", name = "enabled", havingValue = "true")
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    private final Client client;
    private final ObjectMapper objectMapper;
    private final String defaultOwnerId;
    private final String tenantDomain;

    public FeishuClient(
            @Value("${feishu.app-id:}") String appId,
            @Value("${feishu.app-secret:}") String appSecret,
            @Value("${feishu.default-owner-id:}") String defaultOwnerId,
            @Value("${feishu.tenant-domain:}") String tenantDomain,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.defaultOwnerId = defaultOwnerId;
        this.tenantDomain = tenantDomain;
        if (appId.isBlank() || appSecret.isBlank()) {
            log.warn("Feishu app-id/app-secret not configured — Feishu tools will return friendly error messages");
            this.client = null;
        } else {
            this.client = Client.newBuilder(appId, appSecret).build();
            log.info("FeishuClient initialized with appId={}", appId);
        }
    }

    /** 检查是否已正确初始化。 */
    public boolean isAvailable() {
        return client != null;
    }

    // ======================== 文档 API ========================

    /**
     * 创建飞书云文档（仅标题，无内容）。
     *
     * @param title 文档标题
     * @return 文档 ID (document_id)
     */
    public String createDocument(String title) {
        ensureAvailable();
        try {
            CreateDocumentReq req = CreateDocumentReq.newBuilder()
                    .createDocumentReqBody(CreateDocumentReqBody.newBuilder()
                            .title(title)
                            .build())
                    .build();
            CreateDocumentResp resp = client.docx().document().create(req);
            if (!resp.success()) {
                throw new FeishuApiException("创建文档失败", resp.getCode(), resp.getMsg());
            }
            String docId = resp.getData().getDocument().getDocumentId();
            log.info("Feishu doc created: id={}, title={}", docId, title);
            return docId;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("创建文档异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 获取文档的纯文本/Markdown 内容。
     *
     * @param documentId 文档 ID
     * @return Markdown 格式的文档内容
     */
    public String getDocumentRawContent(String documentId) {
        ensureAvailable();
        try {
            RawContentDocumentReq req = RawContentDocumentReq.newBuilder()
                    .documentId(documentId)
                    .build();
            RawContentDocumentResp resp = client.docx().document().rawContent(req);
            if (!resp.success()) {
                throw new FeishuApiException("读取文档失败", resp.getCode(), resp.getMsg());
            }
            return resp.getData().getContent();
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("读取文档异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 将 Markdown 内容转换为飞书 Block 并追加到文档。
     *
     * @param documentId 文档 ID
     * @param markdown   Markdown 内容
     */
    public void convertAndAddContent(String documentId, String markdown) {
        ensureAvailable();
        try {
            // Step 1: 调用 Convert API 将 Markdown 转换为 Block 结构
            // 注意：正确的端点是 /open-apis/docx/v1/documents/blocks/convert（不含 documentId）
            // 必须传入 Map 对象而非预序列化的 String，避免 SDK 的 Gson 二次序列化
            Map<String, Object> body = new HashMap<>();
            body.put("content", markdown);
            body.put("content_type", "markdown");
            RawResponse convertResp = client.post(
                    "/open-apis/docx/v1/documents/blocks/convert",
                    body,
                    AccessTokenType.Tenant
            );
            String convertRespBody = new String(convertResp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> convertResult = objectMapper.readValue(convertRespBody, Map.class);
            int convertCode = ((Number) convertResult.getOrDefault("code", -1)).intValue();
            if (convertCode != 0) {
                String msg = (String) convertResult.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("转换文档内容失败", convertCode, msg);
            }

            // 提取 blocks 列表
            Map<String, Object> data = (Map<String, Object>) convertResult.get("data");
            if (data == null || data.get("blocks") == null) {
                log.info("No blocks to add for documentId={}", documentId);
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> blocks = (List<Object>) data.get("blocks");
            if (blocks.isEmpty()) {
                log.info("Empty blocks for documentId={}", documentId);
                return;
            }

            // Step 2: 将 blocks 作为页面子块写入文档
            // 飞书 docx 中，文档根 page block 的 block_id 等于 document_id
            Map<String, Object> createBody = new HashMap<>();
            createBody.put("children", blocks);
            RawResponse createResp = client.post(
                    "/open-apis/docx/v1/documents/" + documentId
                            + "/blocks/" + documentId + "/children",
                    createBody,
                    AccessTokenType.Tenant
            );
            String createRespBody = new String(createResp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> createResult = objectMapper.readValue(createRespBody, Map.class);
            int createCode = ((Number) createResult.getOrDefault("code", -1)).intValue();
            if (createCode != 0) {
                String msg = (String) createResult.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("添加文档内容失败", createCode, msg);
            }

            log.info("Feishu doc content added: documentId={}, blockCount={}",
                    documentId, blocks.size());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("转换文档内容异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 消息 API ========================

    /**
     * 发送文本消息。
     *
     * @param receiveId   接收者 ID（open_id / user_id）
     * @param receiveType ID 类型：open_id / user_id / chat_id
     * @param text        消息文本
     * @return 消息 ID
     */
    public String sendMessage(String receiveId, String receiveType, String text) {
        ensureAvailable();
        try {
            String contentJson = "{\"text\":\"" + escapeJson(text) + "\"}";
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType(receiveType)
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(receiveId)
                            .msgType("text")
                            .content(contentJson)
                            .build())
                    .build();
            CreateMessageResp resp = client.im().message().create(req);
            if (!resp.success()) {
                throw new FeishuApiException("发送消息失败", resp.getCode(), resp.getMsg());
            }
            String messageId = resp.getData().getMessageId();
            log.info("Feishu message sent: receiveId={}, messageId={}", receiveId, messageId);
            return messageId;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("发送消息异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 搜索 API ========================

    /**
     * 搜索飞书云文档。
     * <p>
     * 使用 Search v2 API 按关键词搜索文档和知识库内容。
     * 需要在飞书开放平台为应用开启「搜索云文档」权限。
     * </p>
     *
     * @param keyword 搜索关键词
     * @return 文档列表的描述文本
     */
    public String searchDocuments(String keyword) {
        ensureAvailable();
        try {
            // 使用 Search v2 API 搜索文档
            // 注意：必须同时传入 doc_filter 或 wiki_filter
            // 必须传入 Map 对象而非预序列化的 String，避免 SDK 的 Gson 二次序列化
            Map<String, Object> body = new HashMap<>();
            body.put("query", keyword);
            body.put("doc_filter", new HashMap<>());
            body.put("page_size", 20);
            RawResponse resp = client.post(
                    "/open-apis/search/v2/doc_wiki/search",
                    body,
                    AccessTokenType.Tenant
            );
            String responseBody = new String(resp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            int code = ((Number) parsed.getOrDefault("code", -1)).intValue();
            if (code != 0) {
                String msg = (String) parsed.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("搜索文档失败", code, msg);
            }
            return responseBody;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("搜索文档异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 审批 API (Approval) ========================

    /**
     * 查询审批实例列表。
     *
     * @param approvalCode    审批定义 Code（可选，不传查所有）
     * @param instanceStatus  审批实例状态（PENDING/APPROVED/REJECTED/CANCELED 等，可选）
     * @param pageSize        每页条数（默认 20）
     * @param pageToken       分页标记（可选）
     * @return 审批实例列表 JSON
     */
    public String queryApprovalInstances(String approvalCode, String instanceStatus,
                                         Integer pageSize, String pageToken) {
        ensureAvailable();
        try {
            com.lark.oapi.service.approval.v4.model.InstanceSearch searchBody =
                    com.lark.oapi.service.approval.v4.model.InstanceSearch.newBuilder()
                            .approvalCode(approvalCode)
                            .instanceStatus(instanceStatus)
                            .build();
            com.lark.oapi.service.approval.v4.model.QueryInstanceReq req =
                    com.lark.oapi.service.approval.v4.model.QueryInstanceReq.newBuilder()
                            .pageSize(pageSize != null ? pageSize : 20)
                            .pageToken(pageToken)
                            .instanceSearch(searchBody)
                            .build();
            com.lark.oapi.service.approval.v4.model.QueryInstanceResp resp =
                    client.approval().instance().query(req);
            if (!resp.success()) {
                throw new FeishuApiException("查询审批实例失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("查询审批实例异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 审批通过。
     *
     * @param approvalCode  审批定义 Code
     * @param instanceCode  审批实例 Code
     * @param taskId        任务 ID
     * @param userId        操作用户 ID
     * @param comment       审批意见（可选）
     * @return 审批结果 JSON
     */
    public String approveTask(String approvalCode, String instanceCode,
                              String taskId, String userId, String comment) {
        ensureAvailable();
        try {
            com.lark.oapi.service.approval.v4.model.TaskApprove taskApprove =
                    com.lark.oapi.service.approval.v4.model.TaskApprove.newBuilder()
                            .approvalCode(approvalCode)
                            .instanceCode(instanceCode)
                            .taskId(taskId)
                            .userId(userId)
                            .comment(comment)
                            .build();
            com.lark.oapi.service.approval.v4.model.ApproveTaskReq req =
                    com.lark.oapi.service.approval.v4.model.ApproveTaskReq.newBuilder()
                            .taskApprove(taskApprove)
                            .build();
            com.lark.oapi.service.approval.v4.model.ApproveTaskResp resp =
                    client.approval().task().approve(req);
            if (!resp.success()) {
                throw new FeishuApiException("审批通过失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("审批通过异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 审批拒绝。
     *
     * @param approvalCode  审批定义 Code
     * @param instanceCode  审批实例 Code
     * @param taskId        任务 ID
     * @param userId        操作用户 ID
     * @param comment       拒绝原因（可选）
     * @return 审批结果 JSON
     */
    public String rejectTask(String approvalCode, String instanceCode,
                             String taskId, String userId, String comment) {
        ensureAvailable();
        try {
            com.lark.oapi.service.approval.v4.model.TaskApprove taskApprove =
                    com.lark.oapi.service.approval.v4.model.TaskApprove.newBuilder()
                            .approvalCode(approvalCode)
                            .instanceCode(instanceCode)
                            .taskId(taskId)
                            .userId(userId)
                            .comment(comment)
                            .build();
            com.lark.oapi.service.approval.v4.model.RejectTaskReq req =
                    com.lark.oapi.service.approval.v4.model.RejectTaskReq.newBuilder()
                            .taskApprove(taskApprove)
                            .build();
            com.lark.oapi.service.approval.v4.model.RejectTaskResp resp =
                    client.approval().task().reject(req);
            if (!resp.success()) {
                throw new FeishuApiException("审批拒绝失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("审批拒绝异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 云盘 API (Drive) ========================

    /**
     * 搜索云盘文件。
     * <p>
     * 使用 Search v2 API 按关键词搜索云盘中的文档和文件。
     * 需要在飞书开放平台为应用开启「搜索云文档」权限。
     * </p>
     *
     * @param keyword 搜索关键词
     * @param pageSize 每页条数（默认 20）
     * @param pageToken 分页标记（可选）
     * @return 文件列表 JSON
     */
    public String searchDriveFiles(String keyword, Integer pageSize, String pageToken) {
        ensureAvailable();
        try {
            // 使用 Search v2 API 搜索云盘文件
            // 必须传入 Map 对象而非预序列化的 String，避免 SDK 的 Gson 二次序列化
            Map<String, Object> body = new HashMap<>();
            body.put("query", keyword);
            body.put("doc_filter", new HashMap<>());
            body.put("page_size", pageSize != null ? pageSize : 20);
            if (pageToken != null && !pageToken.isBlank()) {
                body.put("page_token", pageToken);
            }
            RawResponse resp = client.post(
                    "/open-apis/search/v2/doc_wiki/search",
                    body,
                    AccessTokenType.Tenant
            );
            String responseBody = new String(resp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
            int code = ((Number) parsed.getOrDefault("code", -1)).intValue();
            if (code != 0) {
                String msg = (String) parsed.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("搜索云盘文件失败", code, msg);
            }
            return responseBody;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("搜索云盘文件异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 日历 API (Calendar) ========================

    /**
     * 查询日历事件列表。
     *
     * @param calendarId 日历 ID
     * @param pageSize   每页条数（默认 20）
     * @param pageToken  分页标记（可选）
     * @param startTime  时间范围起始（Unix 秒级时间戳字符串，可选）
     * @param endTime    时间范围结束（Unix 秒级时间戳字符串，可选）
     * @return 事件列表 JSON
     */
    public String listCalendarEvents(String calendarId, Integer pageSize,
                                     String pageToken, String startTime, String endTime) {
        ensureAvailable();
        try {
            com.lark.oapi.service.calendar.v4.model.ListCalendarEventReq req =
                    com.lark.oapi.service.calendar.v4.model.ListCalendarEventReq.newBuilder()
                            .calendarId(calendarId)
                            .pageSize(pageSize != null ? pageSize : 20)
                            .pageToken(pageToken)
                            .startTime(startTime)
                            .endTime(endTime)
                            .build();
            com.lark.oapi.service.calendar.v4.model.ListCalendarEventResp resp =
                    client.calendar().calendarEvent().list(req);
            if (!resp.success()) {
                throw new FeishuApiException("查询日历事件失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("查询日历事件异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 创建日历事件。
     *
     * @param calendarId  日历 ID
     * @param summary     事件标题
     * @param description 事件描述（可选）
     * @param startTs     开始时间（Unix 秒级时间戳字符串）
     * @param endTs       结束时间（Unix 秒级时间戳字符串）
     * @param timezone    时区（如 "Asia/Shanghai"，可选）
     * @return 创建结果 JSON
     */
    public String createCalendarEvent(String calendarId, String summary,
                                      String description, String startTs, String endTs,
                                      String timezone) {
        ensureAvailable();
        try {
            com.lark.oapi.service.calendar.v4.model.TimeInfo startTime =
                    com.lark.oapi.service.calendar.v4.model.TimeInfo.newBuilder()
                            .timestamp(startTs)
                            .timezone(timezone != null ? timezone : "Asia/Shanghai")
                            .build();
            com.lark.oapi.service.calendar.v4.model.TimeInfo endTime =
                    com.lark.oapi.service.calendar.v4.model.TimeInfo.newBuilder()
                            .timestamp(endTs)
                            .timezone(timezone != null ? timezone : "Asia/Shanghai")
                            .build();
            com.lark.oapi.service.calendar.v4.model.CalendarEvent event =
                    com.lark.oapi.service.calendar.v4.model.CalendarEvent.newBuilder()
                            .summary(summary)
                            .description(description)
                            .startTime(startTime)
                            .endTime(endTime)
                            .build();
            com.lark.oapi.service.calendar.v4.model.CreateCalendarEventReq req =
                    com.lark.oapi.service.calendar.v4.model.CreateCalendarEventReq.newBuilder()
                            .calendarId(calendarId)
                            .calendarEvent(event)
                            .build();
            com.lark.oapi.service.calendar.v4.model.CreateCalendarEventResp resp =
                    client.calendar().calendarEvent().create(req);
            if (!resp.success()) {
                throw new FeishuApiException("创建日历事件失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("创建日历事件异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 多维表格 API (Bitable) ========================

    /**
     * 查询 Bitable 记录列表。
     *
     * @param appToken  多维表格 Base Token
     * @param tableId   数据表 ID
     * @param pageSize  每页条数（默认 20）
     * @param pageToken 分页标记（可选）
     * @return 查询结果 JSON
     */
    public String listBitableRecords(String appToken, String tableId,
                                     Integer pageSize, String pageToken) {
        ensureAvailable();
        try {
            ListAppTableRecordReq req = ListAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .pageSize(pageSize != null ? pageSize : 20)
                    .pageToken(pageToken)
                    .build();
            ListAppTableRecordResp resp = client.bitable().appTableRecord().list(req);
            if (!resp.success()) {
                throw new FeishuApiException("查询 Bitable 记录失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("查询 Bitable 记录异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 新增 Bitable 记录。
     *
     * @param appToken 多维表格 Base Token
     * @param tableId  数据表 ID
     * @param fields   字段键值对
     * @return 创建结果 JSON
     */
    public String createBitableRecord(String appToken, String tableId,
                                      Map<String, Object> fields) {
        ensureAvailable();
        try {
            AppTableRecord record = AppTableRecord.newBuilder()
                    .fields(fields)
                    .build();
            CreateAppTableRecordReq req = CreateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .appTableRecord(record)
                    .build();
            CreateAppTableRecordResp resp = client.bitable().appTableRecord().create(req);
            if (!resp.success()) {
                throw new FeishuApiException("新增 Bitable 记录失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("新增 Bitable 记录异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 更新 Bitable 记录。
     *
     * @param appToken  多维表格 Base Token
     * @param tableId   数据表 ID
     * @param recordId  记录 ID
     * @param fields    字段键值对
     * @return 更新结果 JSON
     */
    public String updateBitableRecord(String appToken, String tableId,
                                      String recordId, Map<String, Object> fields) {
        ensureAvailable();
        try {
            AppTableRecord record = AppTableRecord.newBuilder()
                    .fields(fields)
                    .build();
            UpdateAppTableRecordReq req = UpdateAppTableRecordReq.newBuilder()
                    .appToken(appToken)
                    .tableId(tableId)
                    .recordId(recordId)
                    .appTableRecord(record)
                    .build();
            UpdateAppTableRecordResp resp = client.bitable().appTableRecord().update(req);
            if (!resp.success()) {
                throw new FeishuApiException("更新 Bitable 记录失败", resp.getCode(), resp.getMsg());
            }
            return objectMapper.writeValueAsString(resp.getData());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("更新 Bitable 记录异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    // ======================== 用户搜索 API ========================

    /**
     * 搜索飞书用户/成员。
     *
     * @param keyword 搜索关键词（姓名、邮箱等）
     * @param pageSize 每页条数（默认 20）
     * @return 用户列表 JSON
     */
    public String searchUsers(String keyword, Integer pageSize) {
        ensureAvailable();
        try {
            String query = "query=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                    + "&page_size=" + (pageSize != null ? pageSize : 20);
            RawResponse resp = client.get(
                    "/open-apis/contact/v3/users?" + query,
                    null,
                    AccessTokenType.Tenant
            );
            String body = new String(resp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            int code = ((Number) parsed.getOrDefault("code", -1)).intValue();
            if (code != 0) {
                String msg = (String) parsed.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("搜索用户失败", code, msg);
            }
            return body;
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("搜索用户异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 替换飞书云文档的全部内容（清空后重写）。
     * <p>
     * 注意：此操作会清空文档的所有现有内容（包括图片、表格等），
     * 然后写入新的 Markdown 内容。文档的版本历史会被清空。
     * </p>
     *
     * @param documentId 文档 ID
     * @param markdown   新的 Markdown 内容
     */
    public void replaceDocumentContent(String documentId, String markdown) {
        ensureAvailable();
        try {
            // Step 1: 查询文档当前 children 数量
            RawResponse listResp = client.get(
                    "/open-apis/docx/v1/documents/" + documentId
                            + "/blocks/" + documentId + "/children?page_size=500",
                    null,
                    AccessTokenType.Tenant
            );
            String listRespBody = new String(listResp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> listResult = objectMapper.readValue(listRespBody, Map.class);
            int listCode = ((Number) listResult.getOrDefault("code", -1)).intValue();
            if (listCode != 0) {
                String msg = (String) listResult.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("查询文档内容失败", listCode, msg);
            }

            int childCount = 0;
            Map<String, Object> listData = (Map<String, Object>) listResult.get("data");
            if (listData != null) {
                List<Object> items = (List<Object>) listData.get("items");
                childCount = items != null ? items.size() : 0;
            }

            // Step 2: 删除全部现有 children
            if (childCount > 0) {
                Map<String, Object> deleteBody = new HashMap<>();
                deleteBody.put("start_index", 0);
                deleteBody.put("end_index", childCount);
                RawResponse deleteResp = client.post(
                        "/open-apis/docx/v1/documents/" + documentId
                                + "/blocks/" + documentId + "/children/batch_delete",
                        deleteBody,
                        AccessTokenType.Tenant
                );
                String deleteRespBody = new String(deleteResp.getBody(), StandardCharsets.UTF_8);
                Map<String, Object> deleteResult = objectMapper.readValue(deleteRespBody, Map.class);
                int deleteCode = ((Number) deleteResult.getOrDefault("code", -1)).intValue();
                if (deleteCode != 0) {
                    String msg = (String) deleteResult.getOrDefault("msg", "未知错误");
                    throw new FeishuApiException("清空文档内容失败", deleteCode, msg);
                }
                log.info("Feishu doc cleared: documentId={}, removed={} blocks", documentId, childCount);
            }

            // Step 3: Convert Markdown → Block 结构
            Map<String, Object> convertBody = new HashMap<>();
            convertBody.put("content", markdown);
            convertBody.put("content_type", "markdown");
            RawResponse convertResp = client.post(
                    "/open-apis/docx/v1/documents/blocks/convert",
                    convertBody,
                    AccessTokenType.Tenant
            );
            String convertRespBody = new String(convertResp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> convertResult = objectMapper.readValue(convertRespBody, Map.class);
            int convertCode = ((Number) convertResult.getOrDefault("code", -1)).intValue();
            if (convertCode != 0) {
                String msg = (String) convertResult.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("转换文档内容失败", convertCode, msg);
            }

            Map<String, Object> convertData = (Map<String, Object>) convertResult.get("data");
            if (convertData == null || convertData.get("blocks") == null) {
                log.info("No blocks to write for documentId={}", documentId);
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> blocks = (List<Object>) convertData.get("blocks");
            if (blocks.isEmpty()) {
                log.info("Empty blocks for documentId={}", documentId);
                return;
            }

            // Step 4: 写入新的 blocks
            Map<String, Object> createBody = new HashMap<>();
            createBody.put("children", blocks);
            RawResponse createResp = client.post(
                    "/open-apis/docx/v1/documents/" + documentId
                            + "/blocks/" + documentId + "/children",
                    createBody,
                    AccessTokenType.Tenant
            );
            String createRespBody = new String(createResp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> createResult = objectMapper.readValue(createRespBody, Map.class);
            int createCode = ((Number) createResult.getOrDefault("code", -1)).intValue();
            if (createCode != 0) {
                String msg = (String) createResult.getOrDefault("msg", "未知错误");
                throw new FeishuApiException("写入文档内容失败", createCode, msg);
            }

            log.info("Feishu doc replaced: documentId={}, blockCount={}", documentId, blocks.size());
        } catch (FeishuApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FeishuApiException("替换文档内容异常: " + e.getMessage(), -1, e.getMessage());
        }
    }

    /**
     * 获取文档的可访问 URL。
     *
     * @param documentId 文档 ID
     * @return 文档 URL，如果未配置 tenant-domain 则返回 null
     */
    public String getDocumentUrl(String documentId) {
        if (tenantDomain == null || tenantDomain.isBlank()) {
            return null;
        }
        return "https://" + tenantDomain + ".feishu.cn/docx/" + documentId;
    }

    /**
     * 将文档所有权转移给默认用户。
     * <p>
     * 如果配置了 feishu.default-owner-id，创建文档后自动调用此方法，
     * 将文档从应用空间转移到用户的个人空间。
     * 转移后应用仍保留 full_access 权限，可继续编辑文档。
     * </p>
     *
     * @param documentId 文档 ID
     */
    public void transferDocumentOwnership(String documentId) {
        if (defaultOwnerId == null || defaultOwnerId.isBlank()) {
            return;
        }
        ensureAvailable();
        try {
            log.info("Feishu doc ownership transfer starting: documentId={}, owner={}",
                    documentId, defaultOwnerId);
            TransferOwnerPermissionMemberReq req = TransferOwnerPermissionMemberReq.newBuilder()
                    .token(documentId)
                    .type("doc")
                    .needNotification(false)
                    .removeOldOwner(false)
                    .owner(Owner.newBuilder()
                            .memberType("openid")
                            .memberId(defaultOwnerId)
                            .build())
                    .build();
            TransferOwnerPermissionMemberResp resp = client.drive().permissionMember().transferOwner(req);
            if (!resp.success()) {
                log.warn("Feishu doc ownership transfer failed: code={}, msg={}",
                        resp.getCode(), resp.getMsg());
            } else {
                log.info("Feishu doc ownership transferred: documentId={}, owner={}",
                        documentId, defaultOwnerId);
            }
        } catch (Exception e) {
            log.warn("Feishu doc ownership transfer error: {}", e.getMessage());
        }
    }

    /** 是否配置了默认所有者。 */
    public boolean hasDefaultOwner() {
        return defaultOwnerId != null && !defaultOwnerId.isBlank();
    }

    /**
     * 将文档设置为组织内可阅读。
     * <p>调用飞书 Drive 权限公开 API，将文档设为"组织内可阅读"，
     * 组织内所有成员通过链接即可查看，无需额外授权。</p>
     *
     * @param documentId 文档 ID（同时也是 Drive file_token）
     */
    public void setDocumentPublicToTenant(String documentId) {
        ensureAvailable();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("external_access_entity", "closed");
            body.put("security_entity", "anyone_can_view");
            body.put("comment_entity", "anyone_can_view");
            body.put("share_entity", "anyone");
            body.put("link_share_entity", "anyone_readable");
            RawResponse resp = client.patch(
                    "/open-apis/drive/v1/permissions/" + documentId + "/public",
                    body,
                    AccessTokenType.Tenant
            );
            String respBody = new String(resp.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> result = objectMapper.readValue(respBody, Map.class);
            int code = ((Number) result.getOrDefault("code", -1)).intValue();
            if (code != 0) {
                String msg = (String) result.getOrDefault("msg", "未知错误");
                log.warn("Feishu set public to tenant failed: code={}, msg={}", code, msg);
            } else {
                log.info("Feishu doc set to tenant-accessible: documentId={}", documentId);
            }
        } catch (Exception e) {
            log.warn("Feishu set public to tenant error: {}", e.getMessage());
        }
    }

    // ======================== 内部方法 ========================

    private void ensureAvailable() {
        if (client == null) {
            throw new FeishuApiException("飞书应用未配置，请在配置文件中设置 feishu.app-id 和 feishu.app-secret",
                    -1, "client not initialized");
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** 飞书 API 调用异常。 */
    public static class FeishuApiException extends RuntimeException {
        private final int apiCode;
        private final String apiMsg;

        public FeishuApiException(String message, int apiCode, String apiMsg) {
            super(message + " (code=" + apiCode + ", msg=" + apiMsg + ")");
            this.apiCode = apiCode;
            this.apiMsg = apiMsg;
        }

        public int getApiCode() { return apiCode; }
        public String getApiMsg() { return apiMsg; }
    }
}
