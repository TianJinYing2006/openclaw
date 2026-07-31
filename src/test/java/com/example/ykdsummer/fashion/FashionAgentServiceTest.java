package com.example.ykdsummer.fashion;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.orchestration.ToolRegistry;
import com.example.ykdsummer.fashion.agent.DemoFashionConsultationCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class FashionAgentServiceTest {

    private final FashionAgentService service = new FashionAgentService(
            new DemoFashionConsultationCoordinator(),
            new FashionResponseFormatter()
    );

    @Test
    void exposesWechatFashionConsultationTool() {
        ToolCallback callback = ToolCallbacks.from(service)[0];

        assertThat(callback.getToolDefinition().name()).isEqualTo("consult_fashion_outfit");
        assertThat(callback.getToolDefinition().description())
                .contains("今天穿什么", "去海边", "婚礼", "上班", "微信阅读");
        assertThat(callback.getToolDefinition().inputSchema())
                .contains("userInput", "用户原始穿搭需求", "userId");
    }

    @Test
    void toolRegistryCollectsFashionConsultationEntry() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    DemoFashionConsultationCoordinator.class,
                    FashionResponseFormatter.class,
                    FashionAgentService.class,
                    ToolRegistry.class
            );
            context.refresh();

            ToolRegistry registry = context.getBean(ToolRegistry.class);

            assertThat(registry.find("consult_fashion_outfit")).isPresent();
            assertThat(registry.allToolMeta())
                    .anySatisfy(meta -> assertThat(meta.description()).contains("穿搭建议", "微信阅读"));
            assertThat(registry.allToolBeans())
                    .anySatisfy(bean -> assertThat(bean).isInstanceOf(FashionAgentService.class));
        }
    }

    @Test
    void wechatMessageCanInvokeToolCallbackWithMockData() {
        ToolCallback callback = ToolCallbacks.from(service)[0];

        String result = callback.call("{\"userInput\":\"今天去海边穿什么？\",\"userId\":\"wx-001\"}");

        assertThat(result)
                .contains("穿搭建议｜海边度假")
                .contains("最终方案")
                .contains("推荐理由")
                .contains("备选风格");
    }

    @Test
    void beachRequestFormatsFinalOutfitReasonAndAlternatives() {
        String result = service.consult("今天去海边穿什么？", "user-1");

        assertThat(result)
                .contains("穿搭建议｜海边度假")
                .contains("最终方案")
                .contains("浅蓝亚麻短袖衬衫")
                .contains("推荐理由")
                .contains("实用建议")
                .contains("备选风格");
    }

    @Test
    void demoCasesCoverDifferentScenes() {
        assertThat(service.consult("周末参加朋友婚礼怎么穿？", "user-1"))
                .contains("婚礼宾客", "避免纯白");
        assertThat(service.consult("明天上班通勤穿什么比较得体？", "user-1"))
                .contains("日常通勤", "办公室");
        assertThat(service.consult("晚上约会吃饭，帮我搭一套。", "user-1"))
                .contains("约会晚餐", "酒红");
        assertThat(service.consult("随便推荐一套日常出门穿搭。", "user-1"))
                .contains("日常出门", "安全方案");
    }
}
