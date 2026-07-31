package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 穿搭推荐服务（对外入口）。
 *
 * <p>注册为 @Tool，由通用 LLM 在检测到穿搭类请求时自动调用。
 * 内部委托给 AgentCoordinator 执行多 Agent 协作管道。
 */
@Component
public class FashionAgentService {

    private static final Logger log = LoggerFactory.getLogger(FashionAgentService.class);

    private final AgentCoordinator coordinator;
    private final FashionResponseFormatter formatter;

    public FashionAgentService(AgentCoordinator coordinator,
                               FashionResponseFormatter formatter) {
        this.coordinator = coordinator;
        this.formatter = formatter;
    }

    /**
     * 穿搭推荐入口。
     *
     * <p>用户输入如 "今天我要去海边，帮我推荐一套穿搭"，
     * 返回经过多 Agent 协作分析的穿搭方案文案。
     *
     * @param userInput 用户的穿搭需求描述
     * @return 格式化的穿搭推荐文案
     */
    @Tool(name = "fashion_consultant",
          description = "AI穿搭推荐入口。用户询问穿什么、怎么搭、帮我配一身、衣服搭配建议、场合着装、" +
                        "海边/婚礼/通勤/约会/旅行/面试等穿搭方案时调用。输入保留用户原话，" +
                        "工具会完成需求分析、穿搭知识检索、多Agent评审并返回适合微信阅读的最终文案。")
    public String consult(
            @ToolParam(description = "用户的穿搭需求描述") String userInput
    ) {
        if (userInput == null || userInput.isBlank()) {
            return "你可以告诉我今天的场景，比如通勤、约会、海边、婚礼或面试，我来给你搭一套。";
        }
        String userId = AgentSessionContext.currentUserId();
        log.info("Fashion consult request from user {}: {}", userId, userInput);

        try {
            FashionRequest request = new FashionRequest(userId, userInput);
            FashionResult result = coordinator.process(request);
            return formatter.format(result);

        } catch (Exception e) {
            log.error("Fashion pipeline unexpected error: {}", e.getMessage(), e);
            return "抱歉，穿搭推荐服务暂时遇到了问题，请稍后再试。";
        }
    }
}
