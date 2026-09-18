package com.wechatbot.fashion.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechatbot.fashion.ai.fashion.look.model.AnalyzedQuery;
import com.wechatbot.fashion.ai.fashion.look.model.CoordinatorOutput;
import com.wechatbot.fashion.ai.fashion.look.model.FashionResult;
import com.wechatbot.fashion.ai.fashion.look.model.StylistOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔离验证「富类型以 JSON 字符串存放」的 checkpoint 往返策略。
 *
 * <p>阶段 3 发现 spring-ai-alibaba-graph 的 {@link SpringAIJacksonStateSerializer} 不保留自定义 record 类型
 * （嵌套 record 反序列化后退化为 LinkedHashMap）。因此本图约定：富类型一律以 JSON 字符串形式存放于状态，
 * 由节点边界用共享 ObjectMapper 自行（反）序列化。本测试验证该策略下类型可安全往返（模拟 Redis 字节级序列化）。
 */
class StateSerializerRoundTripTest {

    private static final SpringAIJacksonStateSerializer SER =
            new SpringAIJacksonStateSerializer((Map<String, Object> m) -> new OverAllState(m));
    private static final ObjectMapper OM = new ObjectMapper();

    private static StylistOutput sampleStylist() {
        return new StylistOutput(List.of(new StylistOutput.OutfitSuggestion(
                1, "优雅浪漫风",
                new StylistOutput.Outfit("白衬衫", "半身裙", "小白鞋", "丝巾"),
                "白+粉", "理由", List.of("约会"), "均码", "002")));
    }

    private static FashionResult sampleResult() {
        StylistOutput s = sampleStylist();
        CoordinatorOutput co = FashionResultBuilders.fromStylist(s);
        return new FashionResult(true, co, s,
                com.wechatbot.fashion.ai.fashion.look.model.CriticOutput.empty(),
                com.wechatbot.fashion.ai.fashion.look.model.TrendOutput.neutral(),
                "rag context [outfit_002]", AnalyzedQuery.fallback("测试"), null, false);
    }

    @Test
    void stylistOutputJsonStringRoundTripKeepsType() throws Exception {
        Map<String, Object> data = Map.of(
                FashionState.STYLIST_OUT, OM.writeValueAsString(sampleStylist()),
                FashionState.QUERY, "今天约会穿什么");
        byte[] bytes = SER.dataToBytes(data);
        Map<String, Object> restored = SER.dataFromBytes(bytes);
        OverAllState state = SER.stateOf(restored);

        String json = state.value(FashionState.STYLIST_OUT, String.class).orElse(null);
        assertNotNull(json, "JSON 字符串状态值应原样保留");
        StylistOutput v = OM.readValue(json, StylistOutput.class);
        assertEquals(1, v.suggestions().size());
        assertEquals("优雅浪漫风", v.suggestions().get(0).styleLabel());
    }

    @Test
    void fashionResultJsonStringRoundTripKeepsType() throws Exception {
        Map<String, Object> data = Map.of(FashionState.RESULT, OM.writeValueAsString(sampleResult()));
        byte[] bytes = SER.dataToBytes(data);
        Map<String, Object> restored = SER.dataFromBytes(bytes);
        OverAllState state = SER.stateOf(restored);

        String json = state.value(FashionState.RESULT, String.class).orElse(null);
        assertNotNull(json);
        FashionResult v = OM.readValue(json, FashionResult.class);
        assertFalse(v.degraded());
        assertEquals("002", v.coordinator().refinedOutfit().referenceOutfitId());
    }
}
