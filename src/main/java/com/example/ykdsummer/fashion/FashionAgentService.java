package com.example.ykdsummer.fashion;

import com.example.ykdsummer.fashion.agent.FashionConsultationCoordinator;
import com.example.ykdsummer.fashion.model.FashionRequest;
import com.example.ykdsummer.fashion.model.FashionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Entry point exposed to the LLM for outfit consultation requests.
 */
@Component
public class FashionAgentService {

    private static final String DEFAULT_USER_ID = "wechat-user";

    private final FashionConsultationCoordinator coordinator;
    private final FashionResponseFormatter formatter;

    public FashionAgentService(FashionConsultationCoordinator coordinator, FashionResponseFormatter formatter) {
        this.coordinator = coordinator;
        this.formatter = formatter;
    }

    @Tool(
            name = "consult_fashion_outfit",
            description = """
                    当用户询问今天穿什么、去海边/婚礼/上班/约会怎么穿、衣服怎么搭配、穿搭建议、配色、鞋包配饰建议时调用。
                    输入应保留用户原始需求，工具会返回适合微信阅读的穿搭方案，包含最终方案、推荐理由、实用建议和备选风格。
                    """
    )
    public String consult(
            @ToolParam(
                    required = true,
                    description = "用户原始穿搭需求，例如：今天去海边穿什么、参加婚礼怎么搭、上班通勤穿什么。"
            )
            String userInput,
            @ToolParam(
                    required = false,
                    description = "可选用户 ID；微信场景可传发送者 ID，未提供时使用默认访客。"
            )
            String userId
    ) {
        FashionRequest request = new FashionRequest(normalizeUserId(userId), normalizeInput(userInput));
        FashionResult result = coordinator.consult(request);
        return formatter.format(result);
    }

    private static String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER_ID;
        }
        return userId.trim();
    }

    private static String normalizeInput(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "帮我推荐一套日常穿搭";
        }
        return userInput.trim();
    }
}
