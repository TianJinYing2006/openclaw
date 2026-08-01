package com.example.ykdsummer.ai.fashion;

import com.example.ykdsummer.ai.fashion.agent.AgentCoordinator;
import com.example.ykdsummer.ai.fashion.model.FashionConversation;
import com.example.ykdsummer.ai.fashion.model.FashionRequest;
import com.example.ykdsummer.ai.fashion.model.FashionResult;
import com.example.ykdsummer.ai.fashion.profile.FashionConversationService;
import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 穿搭推荐服务（对外入口）。
 *
 * <p>注册为 @Tool，由通用 LLM 在检测到穿搭类请求时自动调用。
 * 内部委托给 AgentCoordinator 执行多 Agent 协作管道。
 */
@Component
public class FashionAgentService {

    private static final Logger log = LoggerFactory.getLogger(FashionAgentService.class);

    /** 反馈信号词：命中任一即视为对上一次推荐的反馈，回填到用户画像数据源 */
    private static final List<String> FEEDBACK_SIGNALS = List.of(
            "喜欢", "不喜欢", "满意", "不满意", "好看", "不好看",
            "太正式", "太休闲", "换一", "换个", "换成", "改一下", "不合适"
    );

    private final AgentCoordinator coordinator;
    private final FashionResponseFormatter formatter;
    private final FashionConversationService conversationService;

    public FashionAgentService(AgentCoordinator coordinator,
                               FashionResponseFormatter formatter,
                               FashionConversationService conversationService) {
        this.coordinator = coordinator;
        this.formatter = formatter;
        this.conversationService = conversationService;
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

        // 反馈检测：命中反馈信号词时，将本次输入回填为最近一次推荐的用户反馈（用户画像数据源）
        recordFeedbackIfAny(userId, userInput);

        try {
            FashionRequest request = new FashionRequest(userId, userInput);
            FashionResult result = coordinator.process(request);
            return formatter.format(result);

        } catch (Exception e) {
            log.error("Fashion pipeline unexpected error: {}", e.getMessage(), e);
            return "抱歉，穿搭推荐服务暂时遇到了问题，请稍后再试。";
        }
    }

    /**
     * 检测用户输入是否为对上一次推荐的反馈，并回填到对话记录。
     *
     * <p>命中反馈信号词时，将输入写入该用户最近一条穿搭对话的 user_feedback 字段，
     * 供后续用户画像检索使用。检测失败不影响主流程。
     */
    private void recordFeedbackIfAny(String userId, String userInput) {
        if (conversationService == null) return;
        boolean isFeedback = FEEDBACK_SIGNALS.stream().anyMatch(userInput::contains);
        if (!isFeedback) return;
        try {
            FashionConversation latest = conversationService.findLatest(userId);
            if (latest != null) {
                conversationService.updateFeedback(latest.id(), userInput);
                log.info("Recorded fashion feedback for conversation {}: {}", latest.id(), userInput);
            }
        } catch (Exception e) {
            log.debug("Failed to record fashion feedback: {}", e.getMessage());
        }
    }
}
