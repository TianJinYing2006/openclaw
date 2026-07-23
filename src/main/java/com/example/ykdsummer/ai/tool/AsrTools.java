package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.bot.audio.AudioTranscriptionService;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音转写工具（ASR）。将语音文件转为文字。
 */
@Component
public class AsrTools {

    private static final Logger log = LoggerFactory.getLogger(AsrTools.class);

    private final AudioTranscriptionService transcriptionService;

    public AsrTools(AudioTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @Tool(name = "transcribe_audio", description = "将语音音频文件转为文字内容")
    public String transcribeAudio(
            @ToolParam(description = "音频文件路径（支持 WAV、MP3、M4A 等格式）") String audioPath
    ) {
        try {
            byte[] audioBytes = Files.readAllBytes(Path.of(audioPath));
            return transcriptionService.transcribe(audioBytes)
                    .orElse("语音转写失败：无法识别音频内容");
        } catch (IOException exception) {
            log.warn("Cannot read audio file: {}", audioPath, exception);
            return "无法读取音频文件：" + exception.getMessage();
        }
    }
}
