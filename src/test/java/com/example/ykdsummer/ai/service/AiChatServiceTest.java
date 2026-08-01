package com.example.ykdsummer.ai.service;

import com.example.ykdsummer.ai.config.AiProperties;
import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.model.AiFile;
import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.model.ConversationMessage;
import com.example.ykdsummer.fashion.application.FashionAgentWorkflowContextProvider;
import com.example.ykdsummer.fashion.application.FashionWardrobeDraftCommandHandler;
import com.example.ykdsummer.fashion.tool.FashionWardrobeVisualCommandHandler;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChatServiceTest {

    @Test
    void keepsHistoryPerUserAndClearOnlyAffectsThatUser() {
        AiProperties properties = new AiProperties();
        RecordingGateway gateway = new RecordingGateway();
        AiChatService service = new AiChatService(properties, gateway);

        assertThat(service.answer("user-a", "第一问", List.of())).isEqualTo("回答1");
        assertThat(service.answer("user-a", "继续问", List.of())).isEqualTo("回答2");
        assertThat(service.answer("user-b", "另一个人", List.of())).isEqualTo("回答3");

        assertThat(gateway.requests.get(0).history()).isEmpty();
        assertThat(gateway.requests.get(1).history())
                .extracting(ConversationMessage::text)
                .containsExactly("第一问", "回答1");
        assertThat(gateway.requests.get(2).history()).isEmpty();

        service.clear("user-a");
        service.answer("user-a", "清空以后", List.of());
        assertThat(gateway.requests.get(3).history()).isEmpty();
        assertThat(service.conversationCount()).isEqualTo(2);
    }

    @Test
    void trimsHistoryToConfiguredMessageCount() {
        AiProperties properties = new AiProperties();
        properties.setMaxMemoryMessages(4);
        RecordingGateway gateway = new RecordingGateway();
        AiChatService service = new AiChatService(properties, gateway);

        service.answer("user", "问题1", List.of());
        service.answer("user", "问题2", List.of());
        service.answer("user", "问题3", List.of());
        service.answer("user", "问题4", List.of());

        assertThat(gateway.requests.get(3).history())
                .extracting(ConversationMessage::text)
                .containsExactly("问题2", "回答2", "问题3", "回答3");
    }

    @Test
    void doesNotCallGatewayWhenDisabled() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(false);
        RecordingGateway gateway = new RecordingGateway();
        AiChatService service = new AiChatService(properties, gateway);

        assertThat(service.answer("user", "你好", List.of())).isEqualTo(AiChatService.DISABLED_REPLY);
        assertThat(gateway.requests).isEmpty();
    }

    @Test
    void convertsGatewayFailuresToSafeReplies() {
        AiProperties properties = new AiProperties();

        assertThat(serviceThrowing(properties, AiGatewayException.Kind.AUTHENTICATION)
                .answer("user", "问题", List.of())).isEqualTo(AiChatService.AUTH_ERROR_REPLY);
        assertThat(serviceThrowing(properties, AiGatewayException.Kind.TEMPORARY_UNAVAILABLE)
                .answer("user", "问题", List.of())).isEqualTo(AiChatService.UNAVAILABLE_REPLY);
        assertThat(serviceThrowing(properties, AiGatewayException.Kind.EMPTY_RESPONSE)
                .answer("user", "问题", List.of())).isEqualTo(AiChatService.EMPTY_REPLY);
    }

    @Test
    void sendsFilesOnlyForCurrentTurnAndKeepsFileNamesInMemory() {
        AiProperties properties = new AiProperties();
        RecordingGateway gateway = new RecordingGateway();
        AiChatService service = new AiChatService(properties, gateway);
        AiFile file = new AiFile("notes.txt", "text/plain", new byte[]{1, 2, 3});

        service.answer("user", "总结", List.of(), List.of(file));
        service.answer("user", "继续", List.of());

        assertThat(gateway.requests.get(0).files()).containsExactly(file);
        assertThat(gateway.requests.get(1).files()).isEmpty();
        assertThat(gateway.requests.get(1).history())
                .extracting(ConversationMessage::text)
                .containsExactly("总结\n[本轮附带文件：notes.txt]", "回答1");
    }

    @Test
    void boundsCachedUsersWithCaffeine() {
        AiProperties properties = new AiProperties();
        properties.setMaxMemoryUsers(1);
        RecordingGateway gateway = new RecordingGateway();
        AiChatService service = new AiChatService(properties, gateway);

        service.answer("user-a", "问题A", List.of());
        service.answer("user-b", "问题B", List.of());

        assertThat(service.conversationCount()).isLessThanOrEqualTo(1);
    }

    @Test
    void usesTheDurableWardrobeHandlerOnlyWhenTheModelFails() {
        AiProperties properties = new AiProperties();
        FashionWardrobeDraftCommandHandler handler = mock(FashionWardrobeDraftCommandHandler.class);
        when(handler.handle("user", "把这一版加长一点吧")).thenReturn(Optional.of("已提交真实草稿任务。"));
        AiChatService service = new AiChatService(properties, (history, prompt, images, files) -> {
            throw new AiGatewayException(AiGatewayException.Kind.TEMPORARY_UNAVAILABLE);
        });
        service.setWardrobeDraftCommands(handler);

        assertThat(service.answer("user", "把这一版加长一点吧", List.of())).isEqualTo("已提交真实草稿任务。");
        verify(handler).handle("user", "把这一版加长一点吧");
    }

    @Test
    void executesAnExplicitWardrobeRevisionBeforeCallingTheModel() {
        AiProperties properties = new AiProperties();
        RecordingGateway gateway = new RecordingGateway();
        FashionWardrobeDraftCommandHandler handler = mock(FashionWardrobeDraftCommandHandler.class);
        when(handler.handleExplicitRevision("user", "基于原图改瘦一点"))
                .thenReturn(Optional.of("已提交基于原图的修改。"));
        AiChatService service = new AiChatService(properties, gateway);
        service.setWardrobeDraftCommands(handler);

        assertThat(service.answer("user", "基于原图改瘦一点", List.of()))
                .isEqualTo("已提交基于原图的修改。");
        assertThat(gateway.requests).isEmpty();
        verify(handler).handleExplicitRevision("user", "基于原图改瘦一点");
    }

    @Test
    void injectsDurableWorkflowStateWithoutWritingInternalIdsIntoConversationHistory() {
        AiProperties properties = new AiProperties();
        RecordingGateway gateway = new RecordingGateway();
        FashionAgentWorkflowContextProvider context = mock(FashionAgentWorkflowContextProvider.class);
        when(context.contextFor("user")).thenReturn("\n[内部状态 candidateId=secret-candidate]");
        AiChatService service = new AiChatService(properties, gateway);
        service.setFashionWorkflowContext(context);

        service.answer("user", "把这件提取出来", List.of());
        service.answer("user", "进度怎么样", List.of());

        assertThat(gateway.requests.getFirst().prompt()).contains("secret-candidate");
        assertThat(gateway.requests.get(1).history())
                .extracting(ConversationMessage::text)
                .containsExactly("把这件提取出来", "回答1");
        assertThat(gateway.requests.get(1).history())
                .extracting(ConversationMessage::text)
                .noneMatch(value -> value.contains("secret-candidate"));
    }

    @Test
    void sendsExplicitWholeWardrobeImageRequestThroughTheVisualGuard() {
        AiProperties properties = new AiProperties();
        RecordingGateway gateway = new RecordingGateway();
        FashionWardrobeVisualCommandHandler handler = mock(FashionWardrobeVisualCommandHandler.class);
        AiArtifact image = AiArtifact.image(new byte[]{1, 2, 3}, "衣橱第1页", "", 0);
        when(handler.handle(org.mockito.ArgumentMatchers.eq("user"), org.mockito.ArgumentMatchers.eq("查看我目前的衣橱图片"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Optional.of(new AiChatService.AssistantAnswer("已发衣橱图。", List.of(image))));
        AiChatService service = new AiChatService(properties, gateway);
        service.setWardrobeVisualCommands(handler);

        AiChatService.AssistantAnswer answer = service.answerRich("user", "查看我目前的衣橱图片", List.of(), List.of());

        assertThat(answer.text()).isEqualTo("已发衣橱图。");
        assertThat(answer.artifacts()).containsExactly(image);
        assertThat(gateway.requests).isEmpty();
        verify(handler).handle(org.mockito.ArgumentMatchers.eq("user"), org.mockito.ArgumentMatchers.eq("查看我目前的衣橱图片"),
                org.mockito.ArgumentMatchers.anyList());
    }

    private static AiChatService serviceThrowing(AiProperties properties, AiGatewayException.Kind kind) {
        return new AiChatService(properties, (history, prompt, images, files) -> {
            throw new AiGatewayException(kind);
        });
    }

    private static final class RecordingGateway implements LlmGateway {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public ModelReply generate(
                List<ConversationMessage> history,
                String prompt,
                List<AiImage> images,
                List<AiFile> files
        ) {
            requests.add(new Request(List.copyOf(history), prompt, List.copyOf(images), List.copyOf(files)));
            return new ModelReply("回答" + requests.size(), "gpt-5.6-sol");
        }
    }

    private record Request(
            List<ConversationMessage> history,
            String prompt,
            List<AiImage> images,
            List<AiFile> files
    ) {
    }
}
