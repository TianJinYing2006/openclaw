package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.ai.service.LlmGateway;
import com.example.ykdsummer.ai.service.LocalImageAssetStore;
import com.example.ykdsummer.bot.file.FileSessionService;
import com.example.ykdsummer.bot.file.LocalDocumentAssetStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

class ConversationMemoryToolsTest {

    @Test
    void clearsOnlyTheCurrentUsersShortTermConversationAndFileCache() {
        RecordingGateway gateway = new RecordingGateway();
        AiChatService chatService = new AiChatService(new AiProperties(), gateway);
        FileSessionService fileSessions = new FileSessionService();
        ToolArtifactCollector collector = new ToolArtifactCollector();
        ConversationMemoryTools tools = new ConversationMemoryTools(
                chatService, fileSessions, new LocalImageAssetStore(), new LocalDocumentAssetStore(), collector
        );

        chatService.answer("user-a", "第一问", List.of());
        chatService.answer("user-b", "另一位用户", List.of());
        fileSessions.cache("user-a", new AiFile("pending.txt", "text/plain", new byte[]{1}));
        collector.begin("user-a");

        assertThat(callback(tools, "clear_current_memory").call("{}"))
                .contains("已清除当前对话记忆", "没有删除");
        collector.finish();
        assertThat(fileSessions.consume("user-a")).isEmpty();

        chatService.answer("user-a", "清理后第一问", List.of());
        chatService.answer("user-b", "继续", List.of());
        assertThat(gateway.requests.get(2).history()).isEmpty();
        assertThat(gateway.requests.get(3).history())
                .extracting(ConversationMessage::text)
                .containsExactly("另一位用户", "回答2");
    }

    private static final class RecordingGateway implements LlmGateway {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public ModelReply generate(
                List<ConversationMessage> history, String prompt, List<AiImage> images, List<AiFile> files
        ) {
            requests.add(new Request(List.copyOf(history)));
            return new ModelReply("回答" + requests.size(), "test-model");
        }
    }

    private record Request(List<ConversationMessage> history) { }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
