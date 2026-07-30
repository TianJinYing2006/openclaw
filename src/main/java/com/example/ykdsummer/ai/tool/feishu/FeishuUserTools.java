package com.example.ykdsummer.ai.tool.feishu;

import com.example.ykdsummer.ai.tool.feishu.FeishuClient.FeishuApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 飞书用户/成员搜索工具。
 *
 * <p>提供搜索飞书组织内用户的能力，
 * 供 Spring AI Function Calling 自动调用。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.feishu.tools", name = "enabled", havingValue = "true")
public class FeishuUserTools {

    private static final Logger log = LoggerFactory.getLogger(FeishuUserTools.class);

    private final FeishuClient feishuClient;

    public FeishuUserTools(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Tool(name = "feishu_user_search", description = """
            搜索飞书组织内的用户/成员。
            支持按姓名、邮箱等关键词搜索。
            适用于：查找飞书用户信息、获取用户 open_id / user_id 等。
            """)
    public String userSearch(
            @ToolParam(required = true, description = "搜索关键词，支持姓名、邮箱等，如 '张三' 或 'zhangsan@company.com'") String keyword,
            @ToolParam(required = false, description = "每页返回的用户数，默认 20") Integer pageSize
    ) {
        log.info("Feishu user search: keyword={}, pageSize={}", keyword, pageSize);
        try {
            String result = feishuClient.searchUsers(keyword, pageSize);
            log.info("Feishu user search success: resultLength={}", result.length());
            return result;
        } catch (FeishuApiException e) {
            log.warn("Feishu user search failed: {}", e.getMessage());
            return "【搜索失败】" + e.getMessage();
        } catch (Exception e) {
            log.error("Feishu user search error", e);
            return "【搜索异常】" + e.getMessage();
        }
    }
}
