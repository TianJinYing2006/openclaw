package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.bot.video.VideoAnalysisService;
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
 * 视频分析工具。分析视频画面和音频内容。
 */
@Component
public class VideoAnalysisTools {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisTools.class);

    private final VideoAnalysisService videoAnalysisService;

    public VideoAnalysisTools(VideoAnalysisService videoAnalysisService) {
        this.videoAnalysisService = videoAnalysisService;
    }

    @Tool(name = "analyze_video", description = "分析视频内容，包括画面和音频，返回文字描述")
    public String analyzeVideo(
            @ToolParam(description = "视频文件路径") String videoPath,
            @ToolParam(description = "关于视频的问题，例如'这个视频讲了什么'，不传则默认概括") String question
    ) {
        try {
            byte[] videoBytes = Files.readAllBytes(Path.of(videoPath));
            String result = videoAnalysisService.analyzeVideoBytes("tool-user", question, videoBytes);
            log.info("Video analysis completed for {}", videoPath);
            return result;
        } catch (IOException exception) {
            log.warn("Cannot read video file: {}", videoPath, exception);
            return "无法读取视频文件：" + exception.getMessage();
        }
    }
}
