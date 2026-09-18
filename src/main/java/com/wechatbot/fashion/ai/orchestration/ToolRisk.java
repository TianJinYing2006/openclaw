package com.wechatbot.fashion.ai.orchestration;

/**
 * 工具风险等级：Agent 调用工具前必须知道「这一步会读什么、写什么、花不花钱、碰不碰用户隐私」。
 *
 * <p>分级语义：
 * <ul>
 *   <li>{@link #READ_ONLY}：纯读取公开/自身数据，无副作用、无费用。</li>
 *   <li>{@link #EXTERNAL_DATA}：读取不受信任的外部数据（网页搜索等），存在 Prompt Injection 风险。</li>
 *   <li>{@link #USER_DATA}：读取或改写用户隐私数据（衣橱照片、人物模板）。</li>
 *   <li>{@link #PAID_OPERATION}：消耗付费算力（图片生成、虚拟试衣）。</li>
 *   <li>{@link #SIDE_EFFECT}：产生外部副作用（写衣橱、建/取消提醒）。</li>
 * </ul>
 */
public enum ToolRisk {
    READ_ONLY,
    EXTERNAL_DATA,
    USER_DATA,
    PAID_OPERATION,
    SIDE_EFFECT;

    /** 是否属于需要额外审慎处理的高风险等级（隐私 / 费用 / 副作用）。 */
    public boolean isHighRisk() {
        return this == USER_DATA || this == PAID_OPERATION || this == SIDE_EFFECT;
    }
}
