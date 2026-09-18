package com.wechatbot.fashion.graph.nodes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool_loop 节点规则预筛（P0-2：零开销透传 vs 触发工具循环的关键分界）。
 *
 * <p>只有「命中城市词 + 命中天气/温度词」才进入模型工具循环，其余输入零 LLM 开销透传——
 * 这是 tool_loop 不破坏"简单请求=2 次 LLM"口径（docs/quantitative-metrics.md）的根基。
 */
class ToolLoopNodeTest {

    @Test
    void triggersOnCityPlusWeatherWord() {
        assertTrue(ToolLoopNode.needsTool("今天杭州33度高温，适合穿什么？"));
        assertTrue(ToolLoopNode.needsTool("北京今天有雨，帮我搭一套通勤穿搭"));
        assertTrue(ToolLoopNode.needsTool("上海降温了，穿什么比较合适"));
        assertTrue(ToolLoopNode.needsTool("深圳台风天，出门穿什么"));
    }

    @Test
    void noTriggerWithoutCity() {
        assertFalse(ToolLoopNode.needsTool("今天33度高温，适合穿什么？"));
        assertFalse(ToolLoopNode.needsTool("下雨天适合穿什么"));
        assertFalse(ToolLoopNode.needsTool("这边降温了"));
    }

    @Test
    void noTriggerWithoutWeatherWord() {
        assertFalse(ToolLoopNode.needsTool("推荐适合杭州的穿搭"));
        assertFalse(ToolLoopNode.needsTool("去北京穿什么"));
        assertFalse(ToolLoopNode.needsTool("杭州有什么好玩的地方"));
    }

    @Test
    void handlesBlankAndNull() {
        assertFalse(ToolLoopNode.needsTool(null));
        assertFalse(ToolLoopNode.needsTool(""));
        assertFalse(ToolLoopNode.needsTool("  "));
    }

    @Test
    void triggersWithTemperatureSymbolOnly() {
        assertTrue(ToolLoopNode.needsTool("杭州现在多少度"));
    }
}