package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 语音合成工具（TTS）。将文字转为语音文件。
 */
@Component
public class TtsTools {

    private static final Logger log = LoggerFactory.getLogger(TtsTools.class);

    private final TextToSpeechService ttsService;
    private final FileStorageService fileStorage;

    public TtsTools(TextToSpeechService ttsService, FileStorageService fileStorage) {
        this.ttsService = ttsService;
        this.fileStorage = fileStorage;
    }

    @Tool(name = "text_to_speech", description = "将文字转为语音音频文件，返回文件路径")
    public String textToSpeech(
            @ToolParam(description = "要转为语音的文字内容") String text,
            @ToolParam(description = "音色选择，如 longxiaoxia、longxiaochun 等，不传则使用默认音色") String voice
    ) {
        String effectiveVoice = (voice != null && !voice.isBlank()) ? voice : null;
        TextToSpeechService.SynthesizedAudio audio = ttsService.synthesize(text, null, effectiveVoice)
                .orElse(null);
        if (audio == null || audio.bytes() == null || audio.bytes().length == 0) {
            return "语音合成失败";
        }
        String fileName = "speech_" + System.currentTimeMillis() + ".wav";
        Path outputPath = fileStorage.writeOutput(
                AgentSessionContext.currentUserId(),
                AgentSessionContext.currentSessionId(),
                fileName, audio.bytes());
        log.info("TTS completed, {} bytes -> {}", audio.bytes().length, outputPath);
        return "语音已生成：" + outputPath.toAbsolutePath();
    }
}
