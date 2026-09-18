package com.wechatbot.fashion.ai.orchestration;

/**
 * 工具参数超过 {@link ToolPolicy#maxInputChars()} 上限时抛出。
 *
 * <p>由 {@link BoundedToolCallingManager} 在实际执行工具前拦截，防止模型生成超大参数导致
 * 外部调用/费用/注入面失控。属于工具治理的执行层。
 */
public class ToolInputLimitExceededException extends RuntimeException {

    public ToolInputLimitExceededException(String toolName, int actualChars, int maxChars) {
        super("工具参数超限：" + toolName + " 实际 " + actualChars + " 字符 > 上限 " + maxChars);
    }
}
