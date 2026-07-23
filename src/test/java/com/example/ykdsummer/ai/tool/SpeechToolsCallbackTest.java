package com.example.ykdsummer.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/** 验证模型工具调用会使用当前用户音色合成 MP3，并将结果带回回复层。 */
class SpeechToolsCallbackTest {

    @Test
    void modelToolCallSynthesizesAudioWithCurrentVoice() {
        TextToSpeechService speechService = mock(TextToSpeechService.class);
        TtsVoiceSelectionService voices = mock(TtsVoiceSelectionService.class);
        TtsVoiceSelectionService.VoiceOption voice = new TtsVoiceSelectionService.VoiceOption(
                "龙婉", "cosyvoice-v3-flash", "longwan_v3", "细腻柔声女", List.of());
        when(voices.current("speech-user")).thenReturn(voice);
        when(speechService.synthesize("你好，今天过得怎么样？", "cosyvoice-v3-flash", "longwan_v3"))
                .thenReturn(Optional.of(new TextToSpeechService.SynthesizedAudio("reply.mp3", new byte[]{7, 8, 9})));

        ToolArtifactCollector artifacts = new ToolArtifactCollector();
        artifacts.begin("speech-user");
        SpeechTools tools = new SpeechTools(speechService, voices, artifacts);

        String result = callback(tools, "synthesize_speech")
                .call("{\"text\":\"你好，今天过得怎么样？\"}");

        assertThat(result).contains("语音文件已生成");
        assertThat(artifacts.finish()).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo(AiArtifact.Type.AUDIO);
            assertThat(artifact.fileName()).isEqualTo("reply.mp3");
        });
        verify(speechService).synthesize("你好，今天过得怎么样？", "cosyvoice-v3-flash", "longwan_v3");
    }

    private static ToolCallback callback(Object tools, String name) {
        return java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
