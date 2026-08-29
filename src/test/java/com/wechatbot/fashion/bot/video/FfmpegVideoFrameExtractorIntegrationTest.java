package com.wechatbot.fashion.bot.video;

import com.wechatbot.fashion.ai.model.AiImage;
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

/** 有可用 FFmpeg 时验证真实视频解码；其他机器没有安装时自动跳过。 */
class FfmpegVideoFrameExtractorIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void extractsTenRealJpegFramesFromSixSecondVideo() throws Exception {
        Optional<Path> ffmpeg = findExecutable("ffmpeg.exe", "ffmpeg");
        Optional<Path> ffprobe = findExecutable("ffprobe.exe", "ffprobe");
        assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(), "FFmpeg is not installed");

        Path video = tempDirectory.resolve("sample.mp4");
        Process generator = new ProcessBuilder(
                ffmpeg.get().toString(),
                "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi",
                "-i", "testsrc=duration=6:size=320x240:rate=10",
                "-c:v", "mpeg4",
                "-pix_fmt", "yuv420p",
                video.toString()
        ).inheritIO().start();
        assertThat(generator.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(generator.exitValue()).isZero();

        VideoProcessingProperties properties = new VideoProcessingProperties();
        properties.setFfmpegPath(ffmpeg.get().toString());
        properties.setFfprobePath(ffprobe.get().toString());
        properties.setProcessTimeout(Duration.ofSeconds(30));
        FfmpegVideoFrameExtractor extractor = new FfmpegVideoFrameExtractor(properties);

        var frames = extractor.extract(Files.readAllBytes(video));

        assertThat(frames).hasSize(10);
        assertThat(frames).extracting(VideoFrame::timestampMs)
                .containsExactly(300L, 900L, 1_500L, 2_100L, 2_700L,
                        3_300L, 3_900L, 4_500L, 5_100L, 5_700L);
        assertThat(frames).allSatisfy(frame -> {
            assertThat(frame.image().detail()).isEqualTo(AiImage.Detail.LOW);
            assertThat(frame.image().mediaType()).isEqualTo("image/jpeg");
            assertThat(frame.image().bytes()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        });
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
