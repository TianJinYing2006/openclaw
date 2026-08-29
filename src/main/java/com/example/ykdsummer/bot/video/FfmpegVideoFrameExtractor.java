package com.example.ykdsummer.bot.video;

import com.example.ykdsummer.ai.model.AiImage;
import com.example.ykdsummer.bot.config.VideoProcessingProperties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 使用本机 ffprobe 获取时长，再调用 FFmpeg 按等分区间中点抽取 JPEG 帧。 */
@Component
public class FfmpegVideoFrameExtractor implements VideoFrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegVideoFrameExtractor.class);

    private final VideoProcessingProperties properties;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;

    public FfmpegVideoFrameExtractor(VideoProcessingProperties properties) {
        this.properties = properties;
        this.ffmpegExecutable = resolveExecutable(properties.getFfmpegPath(), "ffmpeg.exe");
        this.ffprobeExecutable = resolveExecutable(properties.getFfprobePath(), "ffprobe.exe");
    }

    @Override
    public List<VideoFrame> extract(byte[] videoBytes) {
        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("ilink-video-");
            Path input = workDirectory.resolve("input-video.bin");
            Files.write(input, videoBytes);

            double durationSeconds = probeDuration(input, workDirectory);
            double maxSeconds = properties.getMaxDuration().toMillis() / 1_000.0;
            if (durationSeconds > maxSeconds) {
                throw new VideoProcessingException("视频不能超过 "
                        + properties.getMaxDuration().toSeconds() + " 秒");
            }

            int frameCount = calculateFrameCount(
                    durationSeconds,
                    properties.getMinFrames(),
                    properties.getMaxFrames(),
                    properties.getSecondsPerFrame()
            );
            List<Long> timestamps = calculateTimestamps(durationSeconds, frameCount);
            List<VideoFrame> frames = new ArrayList<>(frameCount);
            long totalBytes = 0;

            for (int index = 0; index < timestamps.size(); index++) {
                long timestampMs = timestamps.get(index);
                Path output = workDirectory.resolve(String.format(Locale.ROOT, "frame-%02d.jpg", index + 1));
                extractOneFrame(input, output, timestampMs, workDirectory);
                byte[] jpeg = Files.readAllBytes(output);
                if (!isJpeg(jpeg)) {
                    throw new VideoProcessingException("视频画面提取失败，请重新发送");
                }
                totalBytes += jpeg.length;
                if (totalBytes > properties.getMaxTotalFrameSize().toBytes()) {
                    throw new VideoProcessingException("视频画面数据过大，请发送更短的视频");
                }
                frames.add(new VideoFrame(
                        timestampMs,
                        new AiImage("image/jpeg", jpeg, AiImage.Detail.LOW)
                ));
            }
            return List.copyOf(frames);
        } catch (VideoProcessingException exception) {
            log.warn(
                    "Video frame extraction failed, reason={}, ffmpegDetails={}",
                    exception.userMessage(),
                    diagnosticSnippet(workDirectory)
            );
            throw exception;
        } catch (IOException exception) {
            throw new VideoProcessingException("视频临时文件处理失败，请稍后重试", exception);
        } finally {
            deleteRecursively(workDirectory);
        }
    }

    private double probeDuration(Path input, Path workDirectory) throws IOException {
        Path durationOutput = workDirectory.resolve("duration.txt");
        Path errorOutput = workDirectory.resolve("ffprobe-error.txt");
        ProcessBuilder builder = new ProcessBuilder(
                ffprobeExecutable,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString()
        );
        builder.redirectOutput(durationOutput.toFile());
        builder.redirectError(errorOutput.toFile());
        int exitCode = run(builder, properties.getProcessTimeout());
        if (exitCode != 0) {
            throw new VideoProcessingException("无法读取视频格式，请发送常见 MP4 视频");
        }
        try {
            double seconds = Double.parseDouble(Files.readString(durationOutput, StandardCharsets.UTF_8).trim());
            if (!Double.isFinite(seconds) || seconds <= 0) {
                throw new NumberFormatException("duration is not positive");
            }
            return seconds;
        } catch (NumberFormatException exception) {
            throw new VideoProcessingException("无法读取视频时长，请重新发送", exception);
        }
    }

    private void extractOneFrame(Path input, Path output, long timestampMs, Path workDirectory) throws IOException {
        String timestamp = String.format(Locale.ROOT, "%.3f", timestampMs / 1_000.0);
        String size = properties.getMaxFrameDimension() + ":" + properties.getMaxFrameDimension();
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegExecutable,
                "-y",
                "-hide_banner",
                "-loglevel", "error",
                "-ss", timestamp,
                "-i", input.toString(),
                "-frames:v", "1",
                "-vf", "scale=" + size + ":force_original_aspect_ratio=decrease",
                "-q:v", Integer.toString(properties.getJpegQuality()),
                output.toString()
        );
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workDirectory.resolve("ffmpeg.log").toFile()));
        int exitCode = run(builder, properties.getProcessTimeout());
        if (exitCode != 0 || !Files.isRegularFile(output)) {
            throw new VideoProcessingException("视频画面提取失败，请重新发送");
        }
    }

    private static int run(ProcessBuilder builder, Duration timeout) throws IOException {
        final Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new VideoProcessingException("未找到视频解析工具 FFmpeg，请联系管理员配置", exception);
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new VideoProcessingException("视频解析超时，请发送更短的视频");
            }
            return process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new VideoProcessingException("视频解析被中断，请稍后重试", exception);
        }
    }

    static int calculateFrameCount(double durationSeconds, int minFrames, int maxFrames, double secondsPerFrame) {
        int safeMin = Math.max(1, Math.min(minFrames, maxFrames));
        int safeMax = Math.max(safeMin, maxFrames);
        int suggested = (int) Math.ceil(durationSeconds / Math.max(0.1, secondsPerFrame));
        return Math.max(safeMin, Math.min(safeMax, suggested));
    }

    static List<Long> calculateTimestamps(double durationSeconds, int frameCount) {
        List<Long> result = new ArrayList<>(frameCount);
        for (int index = 0; index < frameCount; index++) {
            double seconds = (index + 0.5) * durationSeconds / frameCount;
            result.add(Math.max(0L, Math.round(seconds * 1_000.0)));
        }
        return List.copyOf(result);
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    /**
     * 只在本机日志中保留 FFmpeg 的短错误摘要，便于区分容器、解码器和抽帧问题；
     * 临时目录仍会在 finally 中删除，不保留用户视频或完整命令输出。
     */
    private static String diagnosticSnippet(Path workDirectory) {
        if (workDirectory == null) {
            return "unavailable";
        }
        StringBuilder result = new StringBuilder();
        for (String name : List.of("ffprobe-error.txt", "ffmpeg.log")) {
            Path file = workDirectory.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ")
                        .trim();
                if (text.isBlank()) {
                    continue;
                }
                if (!result.isEmpty()) {
                    result.append(" | ");
                }
                result.append(name).append('=').append(text, 0, Math.min(text.length(), 600));
            } catch (IOException ignored) {
                // 诊断日志不可读不影响原始用户提示和临时文件清理。
            }
        }
        return result.isEmpty() ? "none" : result.toString();
    }

    /**
     * Winget 安装后，已打开的 IntelliJ 可能暂时看不到新的 PATH。此时自动寻找当前用户
     * Winget 包目录；显式配置的绝对路径始终优先，不会被替换。
     */
    public static String resolveExecutable(String configured, String windowsFileName) {
        if (configured == null || configured.isBlank()) {
            return windowsFileName;
        }
        Path configuredPath;
        try {
            configuredPath = Path.of(configured);
        } catch (RuntimeException invalidPath) {
            return configured;
        }
        if (configuredPath.isAbsolute() || Files.isRegularFile(configuredPath)) {
            return configured;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return configured;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return configured;
        }
        Path packages = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
        if (!Files.isDirectory(packages)) {
            return configured;
        }
        try (Stream<Path> paths = Files.walk(packages, 5)) {
            Optional<Path> found = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(windowsFileName))
                    .findFirst();
            return found.map(Path::toString).orElse(configured);
        } catch (IOException ignored) {
            return configured;
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件清理失败不覆盖原始视频处理结果。
                }
            });
        } catch (IOException ignored) {
            // 同上：清理是尽力而为，不向微信用户暴露本机路径。
        }
    }
}
