package com.example.ykdsummer.bot.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.tool.DocumentTools;
import com.example.ykdsummer.ai.tool.ToolArtifactCollector;
import com.example.ykdsummer.bot.document.DocumentTextExtractor;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** 验证文档 Tool 的创建、读取、改版和回退都由 assetId/version 追踪。 */
class DocumentToolsCallbackTest {

    @Test
    void modelToolCallsCreateReadReviseAndRestoreDocumentVersions() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("document-tool-assets"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("document-user");
        DocumentTools tools = new DocumentTools(store, new DocumentTextExtractor(), artifacts);

        String created = callback(tools, "create_document")
                .call("{\"title\":\"测试周报\",\"format\":\"txt\",\"content\":\"第一版内容\"}");
        String assetId = store.current("document-user").orElseThrow().assetId();
        String current = callback(tools, "get_current_document").call("{}");
        String revised = callback(tools, "replace_document_content").call("{\"assetId\":\"" + assetId
                + "\",\"content\":\"第二版内容\",\"format\":\"txt\",\"changeSummary\":\"更新正文\"}");
        String restored = callback(tools, "restore_document_version")
                .call("{\"assetId\":\"" + assetId + "\",\"targetVersion\":1}");

        assertThat(created).contains(assetId, "v1");
        assertThat(current).contains(assetId, "第一版内容");
        assertThat(revised).contains(assetId, "v2");
        assertThat(restored).contains(assetId, "v3", "历史 v1");
        assertThat(store.current("document-user").orElseThrow().version()).isEqualTo(3);
        assertThat(artifacts.finish()).extracting(AiArtifact::type)
                .containsExactly(AiArtifact.Type.DOCUMENT, AiArtifact.Type.DOCUMENT, AiArtifact.Type.DOCUMENT);
    }

    @Test
    void formatOnlyConversionCreatesAPdfArtifactWithoutMakingTheModelRepeatTheFullBody() throws Exception {
        LocalDocumentAssetStore store = new LocalDocumentAssetStore(Files.createTempDirectory("document-tool-assets"));
        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("document-user");
        DocumentTools tools = new DocumentTools(store, new DocumentTextExtractor(), artifacts);

        callback(tools, "create_document")
                .call("{\"title\":\"杭州出行建议\",\"format\":\"docx\",\"content\":\"第一天游览西湖。\"}");
        String assetId = store.current("document-user").orElseThrow().assetId();
        String converted = callback(tools, "convert_document_format")
                .call("{\"assetId\":\"" + assetId + "\",\"targetFormat\":\"pdf\"}");
        var pdf = store.current("document-user").orElseThrow();

        assertThat(converted).contains(assetId, "v2", "pdf");
        assertThat(pdf.fileName()).isEqualTo("杭州出行建议.pdf");
        assertThat(store.readBytes(pdf)).startsWith("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(artifacts.finish()).extracting(AiArtifact::type)
                .containsExactly(AiArtifact.Type.DOCUMENT, AiArtifact.Type.DOCUMENT);
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
