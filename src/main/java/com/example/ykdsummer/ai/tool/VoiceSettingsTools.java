package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.bot.audio.TtsVoiceSelectionService;
import com.example.ykdsummer.ai.service.AiTraceLogger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 语音偏好的管理工具。用户可以自然表达“换成女声”“恢复默认音色”，
 * 模型会决定调用哪一个方法，接入层无需维护关键词分支。
 */
@Component
public class VoiceSettingsTools {
    private final TtsVoiceSelectionService voices;
    private final ToolArtifactCollector artifacts;
    private final AiTraceLogger trace;

    public VoiceSettingsTools(TtsVoiceSelectionService voices, ToolArtifactCollector artifacts) {
        this(voices, artifacts, AiTraceLogger.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VoiceSettingsTools(TtsVoiceSelectionService voices, ToolArtifactCollector artifacts, AiTraceLogger trace) {
        this.voices = voices;
        this.artifacts = artifacts;
        this.trace = trace;
    }

    @Tool(name = "list_voice_options", description = "当用户想知道有哪些可用音色、男声或女声选项时调用。")
    public String listVoiceOptions() {
        trace.toolCall("list_voice_options", "current user");
        String result = voices.listMessage(artifacts.userId());
        trace.toolResult("list_voice_options", "optionCount responseLength=" + result.length());
        return result;
    }

    @Tool(name = "get_current_voice", description = "当用户询问当前正在使用什么音色时调用。")
    public String getCurrentVoice() {
        trace.toolCall("get_current_voice", "current user");
        String result = "当前音色：" + voices.current(artifacts.userId()).display();
        trace.toolResult("get_current_voice", result);
        return result;
    }

    @Tool(name = "set_voice", description = "当用户要求切换、设置、更换音色，或明确要求男声、女声、龙婉等音色时调用。voiceName 应传用户提到的名称。")
    public String setVoice(String voiceName) {
        String safeName = voiceName == null ? "" : voiceName.strip();
        trace.toolCall("set_voice", "voiceName=" + safeName);
        String result = voices.select(artifacts.userId(), safeName)
                .map(option -> "已切换音色：" + option.display() + "。后续语音回复会使用该音色。")
                .orElse("没有找到音色“" + safeName + "”。可先调用 list_voice_options 查询可用音色。");
        trace.toolResult("set_voice", result);
        return result;
    }

    @Tool(name = "reset_voice", description = "当用户要求恢复默认音色时调用。")
    public String resetVoice() {
        trace.toolCall("reset_voice", "current user");
        String result = "已恢复默认音色：" + voices.reset(artifacts.userId()).display();
        trace.toolResult("reset_voice", result);
        return result;
    }
}
