package com.example.ykdsummer.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.tool.ImageTools;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** 验证模型返回图片 tool_call 后，Spring AI 能绑定参数并真正生成可回传的图片资源。 */
class ImageToolsCallbackTest {

    @Test
    void modelToolCallsCreateAndReviseTrackedImage() throws Exception {
        AiImageGenerationService imageService = mock(AiImageGenerationService.class);
        when(imageService.generate(eq("image-user"), anyString())).thenReturn(
                AiImageGenerationService.Result.image(new byte[]{1, 2, 3})
        );
        when(imageService.revise(eq("image-user"), anyString(), anyString())).thenReturn(
                AiImageGenerationService.Result.image(new byte[]{4, 5, 6})
        );
        LocalImageAssetStore store = new LocalImageAssetStore(Files.createTempDirectory("image-tool-assets"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("image-user");
        ImageTools tools = new ImageTools(imageService, store, artifacts);

        String generated = callback(tools, "generate_image")
                .call("{\"prompt\":\"一只戴围巾的橘猫，水彩风格\"}");
        String assetId = store.current("image-user").orElseThrow().assetId();
        String revised = callback(tools, "create_image_revision")
                .call("{\"assetId\":\"" + assetId + "\",\"prompt\":\"保留橘猫，围巾改成蓝色\"}");

        assertThat(generated).contains("图片编号", assetId, "v1");
        assertThat(revised).contains(assetId, "v2");
        assertThat(store.current("image-user").orElseThrow().version()).isEqualTo(2);
        assertThat(artifacts.finish()).extracting(AiArtifact::type)
                .containsExactly(AiArtifact.Type.IMAGE, AiArtifact.Type.IMAGE);
        verify(imageService).generate(eq("image-user"), anyString());
        verify(imageService).revise(eq("image-user"), anyString(), anyString());
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
