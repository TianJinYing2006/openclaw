package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiArtifact;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import com.example.ykdsummer.bot.audio.SpeechSynthesisException;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 模型需要用语音回复时调用的 TTS 工具，使用当前微信用户已选择的音色。 */
@Component
public class SpeechTools {
    private final TextToSpeechService speechService;
    private final TtsVoiceSelectionService voices;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public SpeechTools(TextToSpeechService speechService,
                       TtsVoiceSelectionService voices,
                       ToolArtifactCollector artifacts) {
        this(speechService, voices, artifacts, AiTraceLogger.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SpeechTools(TextToSpeechService speechService,
                       TtsVoiceSelectionService voices,
                       ToolArtifactCollector artifacts,
                       AiTraceLogger trace) {
        this.speechService = speechService;
        this.voices = voices;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "synthesize_speech", description = "当用户明确要求用语音、朗读或语音文件回复时调用。text 是需要朗读的完整简体中文内容；普通文字回答不要调用。若用户同一条要求指定音色，必须先调用 set_voice 并等待成功结果，再调用本工具。")
    public String synthesizeSpeech(String text) {
        String userId = artifacts.userId();
        TtsVoiceSelectionService.VoiceOption voice = voices.current(userId);
        trace.toolCall("synthesize_speech", "textLength=" + (text == null ? 0 : text.strip().length())
                + ", voice=" + voice.voiceId());
        try {
            return speechService.synthesize(text, voice.modelId(), voice.voiceId())
                    .map(audio -> {
                        artifacts.add(AiArtifact.audio(audio.bytes(), audio.fileName(), "已生成语音"));
                        String result = "语音文件已生成并会发送给用户。";
                        trace.toolResult("synthesize_speech", result);
                        return result;
                    })
                    .orElseGet(() -> {
                        String result = "语音生成服务当前不可用，请直接用文字回答。";
                        trace.toolResult("synthesize_speech", result);
                        return result;
                    });
        } catch (SpeechSynthesisException exception) {
            trace.toolFailure("synthesize_speech", exception);
            return "语音生成失败：" + exception.userMessage() + "。请直接用文字回答。";
        }
    }
}
