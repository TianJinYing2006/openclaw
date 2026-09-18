package com.wechatbot.fashion.ai.fashion.look.profile;

import com.wechatbot.fashion.ai.fashion.look.agent.AgentLlmCaller;
import com.wechatbot.fashion.ai.fashion.look.model.InferredPreference;
import com.wechatbot.fashion.wardrobe.application.FashionCoreService;
import com.wechatbot.fashion.wardrobe.domain.FashionPreferenceUpdate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 记忆治理：一次性反馈落 SESSION，普通反馈落 LONG_TERM。 */
class PreferenceInferenceServiceScopeTest {

    @Test
    void oneOffFeedbackIsRecordedAsSessionPreference() {
        AgentLlmCaller llmCaller = mock(AgentLlmCaller.class);
        doReturn(new InferredPreference[]{new InferredPreference("COLOR", "RED", "NEGATIVE")})
                .when(llmCaller).callAgent(anyString(), anyString(), any(), anyInt(), any());

        FashionCoreService coreService = mock(FashionCoreService.class);
        PreferenceInferenceService service = new PreferenceInferenceService(llmCaller);
        service.setCoreService(coreService);

        service.inferAndRecord("user-1", "这次不要红色，太正式了", "NEGATIVE");

        ArgumentCaptor<FashionPreferenceUpdate> captor = ArgumentCaptor.forClass(FashionPreferenceUpdate.class);
        verify(coreService).updatePreference(eq("user-1"), captor.capture());
        FashionPreferenceUpdate update = captor.getValue();
        assertThat(update.scope()).isEqualTo(PreferenceScope.SESSION);
        assertThat(update.expiresAt()).isNotNull();
    }

    @Test
    void generalFeedbackIsRecordedAsLongTerm() {
        AgentLlmCaller llmCaller = mock(AgentLlmCaller.class);
        doReturn(new InferredPreference[]{new InferredPreference("STYLE", "MINIMAL", "POSITIVE")})
                .when(llmCaller).callAgent(anyString(), anyString(), any(), anyInt(), any());

        FashionCoreService coreService = mock(FashionCoreService.class);
        PreferenceInferenceService service = new PreferenceInferenceService(llmCaller);
        service.setCoreService(coreService);

        service.inferAndRecord("user-2", "我平时喜欢简约风格", "POSITIVE");

        ArgumentCaptor<FashionPreferenceUpdate> captor = ArgumentCaptor.forClass(FashionPreferenceUpdate.class);
        verify(coreService).updatePreference(eq("user-2"), captor.capture());
        FashionPreferenceUpdate update = captor.getValue();
        assertThat(update.scope()).isEqualTo(PreferenceScope.LONG_TERM);
        assertThat(update.expiresAt()).isNull();
    }
}
