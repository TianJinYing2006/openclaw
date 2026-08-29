package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.TencentAsrProperties;
import com.wechatbot.fashion.bot.config.VideoProcessingProperties;
import com.wechatbot.fashion.bot.video.FfmpegVideoFrameExtractor;
import com.wechatbot.fashion.bot.video.VideoProcessingException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 使用 FFprobe 判断音轨，再用 FFmpeg 转成 16kHz、单声道、16-bit PCM WAV。 */
@Component
public class FfmpegVideoAudioExtractor implements AudioTrackExtractor {

    private final VideoProcessingProperties videoProperties;
    private final TencentAsrProperties asrProperties;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;

    public FfmpegVideoAudioExtractor(
            VideoProcessingProperties videoProperties,
            TencentAsrProperties asrProperties
    ) {
        this.videoProperties = videoProperties;
        this.asrProperties = asrProperties;
        this.ffmpegExecutable = FfmpegVideoFrameExtractor.resolveExecutable(
                videoProperties.getFfmpegPath(), "ffmpeg.exe");
        this.ffprobeExecutable = FfmpegVideoFrameExtractor.resolveExecutable(
                videoProperties.getFfprobePath(), "ffprobe.exe");
    }

    @Override
    public Optional<byte[]> extract(byte[] videoBytes) {
        // 没有 ASR 凭据时直接跳过，避免明知无法转写还启动一次 FFmpeg 音轨转换。
        if (!asrProperties.isEnabled() || !asrProperties.isConfigured()) {
            return Optional.empty();
        }
        if (videoBytes == null || videoBytes.length == 0) {
            return Optional.empty();
        }
        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("ilink-video-audio-");
            Path input = workDirectory.resolve("input-video.bin");
            Files.write(input, videoBytes);
            if (!hasAudioTrack(input, workDirectory)) {
                return Optional.empty();
            }

            Path output = workDirectory.resolve("audio-16k-mono.wav");
            extractWav(input, output, workDirectory);
            byte[] wav = Files.readAllBytes(output);
            if (!isWav(wav)) {
                throw new VideoProcessingException("视频音轨格式转换失败");
            }
            if (wav.length > asrProperties.getMaxAudioSize().toBytes()) {
                throw new VideoProcessingException("视频音轨过大，暂时无法进行语音识别");
            }
            return Optional.of(wav);
        } catch (VideoProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VideoProcessingException("视频音轨提取失败", exception);
        } finally {
            deleteRecursively(workDirectory);
        }
    }

    private boolean hasAudioTrack(Path input, Path workDirectory) throws IOException {
        Path output = workDirectory.resolve("audio-stream.txt");
        Path error = workDirectory.resolve("ffprobe-audio-error.txt");
        ProcessBuilder builder = new ProcessBuilder(
                ffprobeExecutable,
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_type",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString()
        );
        builder.redirectOutput(output.toFile());
        builder.redirectError(error.toFile());
        int exitCode = run(builder, videoProperties.getProcessTimeout());
        if (exitCode != 0) {
            throw new VideoProcessingException("无法读取视频音轨");
        }
        return Files.readString(output, StandardCharsets.UTF_8).trim().equalsIgnoreCase("audio");
    }

    private void extractWav(Path input, Path output, Path workDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegExecutable,
                "-y", "-hide_banner", "-loglevel", "error",
                "-i", input.toString(),
                "-map", "0:a:0",
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "pcm_s16le",
                output.toString()
        );
        builder.redirectErrorStream(true);
        builder.redirectOutput(workDirectory.resolve("ffmpeg-audio.log").toFile());
        int exitCode = run(builder, videoProperties.getProcessTimeout());
        if (exitCode != 0 || !Files.isRegularFile(output)) {
            throw new VideoProcessingException("视频音轨格式转换失败");
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
                throw new VideoProcessingException("视频音轨提取超时");
            }
            return process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new VideoProcessingException("视频音轨提取被中断", exception);
        }
    }

    private static boolean isWav(byte[] bytes) {
        return bytes != null && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
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
                    // 临时文件清理失败不覆盖本次分析结果。
                }
            });
        } catch (IOException ignored) {
            // 清理尽力而为，日志和回复都不暴露本机临时目录。
        }
    }
}
