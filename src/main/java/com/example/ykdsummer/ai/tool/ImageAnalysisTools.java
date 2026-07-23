package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.ai.service.AiChatService;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 图片识别工具。将图片发送给大模型进行理解。
 */
@Component
public class ImageAnalysisTools {

    private static final Logger log = LoggerFactory.getLogger(ImageAnalysisTools.class);

    private final AiChatService aiChatService;
    private final FileStorageService fileStorage;

    public ImageAnalysisTools(AiChatService aiChatService, FileStorageService fileStorage) {
        this.aiChatService = aiChatService;
        this.fileStorage = fileStorage;
    }

    @Tool(name = "analyze_image", description = "识别图片内容，回答关于图片的问题")
    public String analyzeImage(
            @ToolParam(description = "图片文件路径") String imagePath,
            @ToolParam(description = "关于图片的问题，例如'这张图里有什么'") String question
    ) {
        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(Path.of(imagePath));
        } catch (IOException exception) {
            log.warn("Cannot read image file: {}", imagePath, exception);
            return "无法读取图片文件：" + imagePath;
        }

        String prompt = (question != null && !question.isBlank()) ? question : "请描述这张图片的内容";
        AiImage image = new AiImage("image/png", imageBytes, AiImage.Detail.AUTO);
        String result = aiChatService.answer("tool-user", prompt, List.of(image));
        log.info("Image analysis completed for {}", imagePath);
        return result;
    }
}
