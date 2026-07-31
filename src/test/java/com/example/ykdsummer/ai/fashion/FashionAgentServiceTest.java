package com.example.ykdsummer.ai.fashion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FashionAgentServiceTest {

    @Test
    void exposesFashionConsultantAsSpringAiTool() throws NoSuchMethodException {
        Method consult = FashionAgentService.class.getMethod("consult", String.class);
        Tool tool = consult.getAnnotation(Tool.class);

        assertEquals("fashion_consultant", tool.name());
        assertTrue(tool.description().contains("穿什么"));
        assertTrue(tool.description().contains("海边/婚礼/通勤"));
        assertTrue(tool.description().contains("多Agent"));
    }

    @Test
    void blankInputReturnsGuidanceWithoutCallingPipeline() {
        FashionAgentService service = new FashionAgentService(null, new FashionResponseFormatter());

        String reply = service.consult("  ");

        assertTrue(reply.contains("通勤"));
        assertTrue(reply.contains("约会"));
        assertTrue(reply.contains("面试"));
    }
}
