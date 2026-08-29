package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.TencentAsrProperties;
import com.wechatbot.fashion.bot.config.VideoProcessingProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 本地存在 FFmpeg 时，验证视频音轨确实会变成腾讯 ASR 可接收的 WAV。 */
class FfmpegVideoAudioExtractorIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void extractsSixteenKhzMonoWavFromVideo() throws Exception {
        Optional<Path> ffmpeg = findExecutable("ffmpeg.exe", "ffmpeg");
        Optional<Path> ffprobe = findExecutable("ffprobe.exe", "ffprobe");
        assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(), "FFmpeg is not installed");

        Path video = tempDirectory.resolve("video-with-audio.mp4");
        Process generator = new ProcessBuilder(
                ffmpeg.get().toString(),
                "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=5",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-shortest",
                "-c:v", "mpeg4", "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                video.toString()
        ).inheritIO().start();
        assertThat(generator.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(generator.exitValue()).isZero();

        VideoProcessingProperties videoProperties = new VideoProcessingProperties();
        videoProperties.setFfmpegPath(ffmpeg.get().toString());
        videoProperties.setFfprobePath(ffprobe.get().toString());
        videoProperties.setProcessTimeout(Duration.ofSeconds(30));
        TencentAsrProperties asrProperties = new TencentAsrProperties();
        // 这里只验证本地 FFmpeg，不会联网；测试值仅让提取器进入音轨分支。
        asrProperties.setSecretId("test-secret-id");
        asrProperties.setSecretKey("test-secret-key");
        FfmpegVideoAudioExtractor extractor = new FfmpegVideoAudioExtractor(videoProperties, asrProperties);

        byte[] wav = extractor.extract(Files.readAllBytes(video)).orElseThrow();

        assertThat(wav).startsWith((byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F');
        assertThat(new String(wav, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat((long) wav.length).isLessThan(asrProperties.getMaxAudioSize().toBytes());
    }

    private static Optional<Path> findExecutable(String windowsName, String commandName) throws IOException {
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            Optional<Path> fromPath = Arrays.stream(pathValue.split(java.io.File.pathSeparator))
                    .filter(value -> !value.isBlank())
                    .map(Path::of)
                    .map(directory -> directory.resolve(isWindows() ? windowsName : commandName))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (fromPath.isPresent()) {
                return fromPath;
            }
        }
        if (!isWindows()) {
            return Optional.empty();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return Optional.empty();
        }
        Path packages = Path.of(localAppData, "Microsoft", "WinGet", "Packages");
        if (!Files.isDirectory(packages)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.walk(packages, 5)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(windowsName))
                    .findFirst();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
