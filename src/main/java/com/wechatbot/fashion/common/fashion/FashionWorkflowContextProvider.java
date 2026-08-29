package com.wechatbot.fashion.common.fashion;

/**
 * 每个 Agent 回合前注入的、持久化衣橱工作流上下文的提供方。
 *
 * <p>放在中性共用层 {@code common.fashion}，由 Look 引擎(A) 实现，供对话层(A) 与衣橱测试(B) 依赖此接口
 * 而非具体类，消除衣橱侧对 Look 引擎具体实现的反向耦合（见 docs/architecture/FASHION_BOUNDARIES.md §Full-B）。</p>
 */
public interface FashionWorkflowContextProvider {

    /**
     * 为指定外部用户重建持久化的衣橱工作流上下文文本；无活动工作流时返回空串。
     *
     * @param externalUserId 微信外部用户标识
     * @return 注入给 LLM 的内部上下文文本（含衣橱流程状态 / 最近推荐方案等），无内容时为空串
     */
    String contextFor(String externalUserId);
}
