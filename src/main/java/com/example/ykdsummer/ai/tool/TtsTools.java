package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.bot.audio.TextToSpeechService;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

/**
 * 语音合成工具（TTS）。将文字转为语音文件。
 */
@Component
public class TtsTools {

    private static final Logger log = LoggerFactory.getLogger(TtsTools.class);

    /** AI响应后待发送的音频字节（单线程内传递）。 */
    private static final ThreadLocal<List<PendingAudio>>
            pendingAudios = ThreadLocal.withInitial(ArrayList::new);

    private final TextToSpeechService ttsService;
    private final FileStorageService fileStorage;

    public TtsTools(TextToSpeechService ttsService, FileStorageService fileStorage) {
        this.ttsService = ttsService;
        this.fileStorage = fileStorage;
    }

        @Tool(name = "text_to_speech", description = "将文字转为语音音频文件发送给用户。当用户要求'用语音读出来'、'用语音回我'、'说给我听'、'转语音'等使用语音回复的请求时，必须调用此工具生成语音，而不是只用文字回复。调用后用户会收到一条语音消息。")
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
        // 使用 SynthesizedAudio 的文件名（已包含正确扩展名，如 answer.mp3）
        String fileName = audio.fileName();
        Path outputPath = fileStorage.writeOutput(
                AgentSessionContext.currentUserId(),
                AgentSessionContext.currentSessionId(),
                fileName, audio.bytes());
        log.info("TTS completed, {} bytes -> {}", audio.bytes().length, outputPath);

        // 通过 ThreadLocal 暴露音频字节
        pendingAudios.get().add(new PendingAudio(audio.bytes(), fileName));
        return "语音已生成：" + outputPath.toAbsolutePath();
    }

    /** 消费所有待发送的音频列表。调用后自动清除。 */
    public static List<PendingAudio> consumePendingAudios() {
        List<PendingAudio> list = pendingAudios.get();
        if (list == null || list.isEmpty()) {
            pendingAudios.remove();
            return List.of();
        }
        pendingAudios.remove();
        return list;
    }

    /** 消费最后一个待发送的音频。调用后自动清除。 */
    public static PendingAudio consumePendingAudio() {
        List<PendingAudio> list = consumePendingAudios();
        return list.isEmpty() ? null : list.getLast();
    }

    public record PendingAudio(byte[] bytes, String fileName) {
        public PendingAudio { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
