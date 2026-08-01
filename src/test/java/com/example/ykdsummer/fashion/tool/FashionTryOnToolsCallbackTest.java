package com.example.ykdsummer.fashion.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.fashion.application.FashionVirtualTryOnService;
import com.example.ykdsummer.fashion.domain.FashionTryOnTask;
import com.example.ykdsummer.fashion.domain.FashionTryOnTaskStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class FashionTryOnToolsCallbackTest {

    @Test
    void submitsOnlyForTheCurrentChatUserAndDoesNotExposeInternalTaskId() {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("managed:instance-a:wechat-user");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled());
        when(service.submit("managed:instance-a:wechat-user", 42L)).thenReturn(task(FashionTryOnTaskStatus.SUBMITTED));

        String result = callback(tools, "virtual_try_on_wardrobe_item").call("{\"wardrobeItemId\":42}");

        assertThat(result).contains("后台").doesNotContain("task-tryon-internal");
        verify(service).submit(eq("managed:instance-a:wechat-user"), eq(42L));
        collector.finish();
    }

    @Test
    void reportsPersistedFailureOnlyWhenStatusToolConfirmsIt() {
        FashionVirtualTryOnService service = mock(FashionVirtualTryOnService.class);
        ToolArtifactCollector collector = new ToolArtifactCollector();
        collector.begin("user-b");
        FashionTryOnTools tools = new FashionTryOnTools(service, collector, AiTraceLogger.disabled());
        when(service.latest("user-b")).thenReturn(java.util.Optional.of(task(FashionTryOnTaskStatus.FAILED)));

        assertThat(callback(tools, "check_virtual_tryon_status").call("{}")).contains("没有生成成功", "可以重新试一次");
        collector.finish();
    }

    private static FashionTryOnTask task(FashionTryOnTaskStatus status) {
        Instant now = Instant.now();
        return new FashionTryOnTask("task-tryon-internal", 1L, "", "template", 42L, 2L, 3L,
                status, 0, null, "图片服务超时", null, now, now, now);
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }
}
