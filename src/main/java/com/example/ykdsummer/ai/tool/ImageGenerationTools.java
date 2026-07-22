package com.example.ykdsummer.ai.tool;

import com.example.ykdsummer.ai.orchestration.AgentSessionContext;
import com.example.ykdsummer.ai.provider.ImageGenerationProvider;
import com.example.ykdsummer.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 图片生成工具。由 AI 模型在对话中自行调用。
 *
 * <p>生成的图片保存至本地工作区后返回路径字符串给模型。
 * 同时会将图片字节通过 {@link #pendingImage} 暴露出来，
 * {@code ILinkReplyService} 在 AI 响应后可检查并发送图片到微信。</p>
 */
@Component
public class ImageGenerationTools {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTools.class);

    /** AI 响应后待发送的图片字节（单线程内传递）。 */
    private static final ThreadLocal<PendingImage> pendingImage = new ThreadLocal<>();

    private final ImageGenerationProvider imageGenerationProvider;
    private final FileStorageService fileStorage;

    public ImageGenerationTools(ImageGenerationProvider imageGenerationProvider, FileStorageService fileStorage) {
        this.imageGenerationProvider = imageGenerationProvider;
        this.fileStorage = fileStorage;
    }

    @Tool(name = "generate_image", description = "根据文字描述生成图片，返回图片文件的本地路径")
    public String generateImage(
            @ToolParam(description = "图片描述，越详细生成效果越好") String prompt,
            @ToolParam(description = "图片尺寸，如 1024x1024、1920x1080，不传则使用默认尺寸") String size
    ) {
        String effectiveSize = (size != null && !size.isBlank()) ? size : "1024x1024";
        ImageGenerationProvider.Result result = imageGenerationProvider.generate(prompt, effectiveSize);
        if (!result.success()) {
            return "生成失败：" + result.errorMessage();
        }
        byte[] imageBytes = result.imageBytes();
        Path output = fileStorage.writeOutput(
                AgentSessionContext.currentUserId(),
                AgentSessionContext.currentSessionId(),
                "generated_" + System.currentTimeMillis() + ".png", imageBytes);
        log.info("Image generated, path={}, bytes={}", output.toAbsolutePath(), imageBytes.length);

        // 通过 ThreadLocal 暴露图片字节，供回复层检查后通过 iLink 发送到微信
        pendingImage.set(new PendingImage(imageBytes, output.toAbsolutePath().toString()));
        return "图片已生成：" + output.toAbsolutePath();
    }

    /** 检查是否有待发送的图片。调用后自动清除。 */
    public static byte[] consumePendingImage() {
        PendingImage pi = pendingImage.get();
        if (pi == null) return null;
        pendingImage.remove();
        return pi.bytes();
    }

    public record PendingImage(byte[] bytes, String path) {
        public PendingImage { bytes = bytes.clone(); }
        public byte[] bytes() { return bytes.clone(); }
    }
}
