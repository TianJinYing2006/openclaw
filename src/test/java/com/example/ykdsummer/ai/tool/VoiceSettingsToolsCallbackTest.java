package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** 验证音色查询、切换和恢复默认都可由标准 tool_call 参数触发。 */
class VoiceSettingsToolsCallbackTest {

    @Test
    void modelToolCallsManageOnlyTheCurrentUsersVoice() {
        TtsVoiceSelectionService voices = mock(TtsVoiceSelectionService.class);
        TtsVoiceSelectionService.VoiceOption defaultVoice = new TtsVoiceSelectionService.VoiceOption(
                "龙安洋", "cosyvoice-v3-flash", "longanyang", "阳光大男孩", List.of());
        TtsVoiceSelectionService.VoiceOption selectedVoice = new TtsVoiceSelectionService.VoiceOption(
                "龙婉", "cosyvoice-v3-flash", "longwan_v3", "细腻柔声女", List.of());
        when(voices.listMessage("voice-user")).thenReturn("可用音色：龙安洋、龙婉");
        when(voices.current("voice-user")).thenReturn(defaultVoice);
        when(voices.select("voice-user", "龙婉")).thenReturn(Optional.of(selectedVoice));
        when(voices.reset("voice-user")).thenReturn(defaultVoice);

        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("voice-user");
        VoiceSettingsTools tools = new VoiceSettingsTools(voices, artifacts);

        assertThat(callback(tools, "list_voice_options").call("{}")).contains("龙婉");
        assertThat(callback(tools, "get_current_voice").call("{}")).contains("龙安洋");
        assertThat(callback(tools, "set_voice").call("{\"voiceName\":\"龙婉\"}")).contains("已切换音色", "龙婉");
        assertThat(callback(tools, "reset_voice").call("{}")).contains("已恢复默认音色", "龙安洋");

        artifacts.finish();
        verify(voices).select("voice-user", "龙婉");
        verify(voices).reset("voice-user");
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
