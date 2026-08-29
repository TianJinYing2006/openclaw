package com.wechatbot.fashion.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 微信视频理解的安全限制和本机 FFmpeg 配置。
 *
 * <p>这些限制都发生在 Java 本地：腾讯 iLink 只负责把视频媒体交给 SDK，真正的
 * 下载后校验、抽帧和模型调用由本项目完成。</p>
 */
@ConfigurationProperties(prefix = "app.video")
public class VideoProcessingProperties {

    /** 是否允许处理入站视频；关闭后文字、图片和固定命令仍可使用。 */
    private boolean enabled = true;

    /** 单条视频最大 20 MiB，避免 SDK 解密返回的 byte[] 占用过多内存。 */
    private DataSize maxVideoSize = DataSize.ofMegabytes(20);

    /** 第一版只处理不超过 60 秒的短视频。 */
    private Duration maxDuration = Duration.ofSeconds(60);

    /** 默认固定抽取 10 帧；如需恢复动态抽帧，可通过配置重新设置上下限。 */
    private int minFrames = 10;
    private int maxFrames = 10;

    /** 大约每 3 秒增加一帧，最后仍受 min/maxFrames 限制。 */
    private double secondsPerFrame = 3.0;

    /** 每帧缩放到 768x768 范围内，保持原始宽高比。 */
    private int maxFrameDimension = 768;

    /** FFmpeg 的 JPEG 质量参数，数值越小质量越高；2～5 通常适合模型输入。 */
    private int jpegQuality = 4;

    /** 全部抽帧结果最大 5 MiB。 */
    private DataSize maxTotalFrameSize = DataSize.ofMegabytes(5);

    /** ffprobe 或单次 ffmpeg 进程最长运行时间。 */
    private Duration processTimeout = Duration.ofMinutes(2);

    /** 可以填写绝对路径；默认要求 ffmpeg/ffprobe 已加入系统 PATH。 */
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";

    /** 视频独立队列最多等待 5 条，防止大量视频无限堆积。 */
    private int queueCapacity = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public DataSize getMaxVideoSize() {
        return maxVideoSize;
    }

    public void setMaxVideoSize(DataSize maxVideoSize) {
        this.maxVideoSize = maxVideoSize;
    }

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }

    public int getMinFrames() {
        return minFrames;
    }

    public void setMinFrames(int minFrames) {
        this.minFrames = Math.max(1, minFrames);
    }

    public int getMaxFrames() {
        return maxFrames;
    }

    public void setMaxFrames(int maxFrames) {
        this.maxFrames = Math.max(1, maxFrames);
    }

    public double getSecondsPerFrame() {
        return secondsPerFrame;
    }

    public void setSecondsPerFrame(double secondsPerFrame) {
        this.secondsPerFrame = Math.max(0.1, secondsPerFrame);
    }

    public int getMaxFrameDimension() {
        return maxFrameDimension;
    }

    public void setMaxFrameDimension(int maxFrameDimension) {
        this.maxFrameDimension = Math.max(128, maxFrameDimension);
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public void setJpegQuality(int jpegQuality) {
        this.jpegQuality = Math.max(2, Math.min(31, jpegQuality));
    }

    public DataSize getMaxTotalFrameSize() {
        return maxTotalFrameSize;
    }

    public void setMaxTotalFrameSize(DataSize maxTotalFrameSize) {
        this.maxTotalFrameSize = maxTotalFrameSize;
    }

    public Duration getProcessTimeout() {
        return processTimeout;
    }

    public void setProcessTimeout(Duration processTimeout) {
        this.processTimeout = processTimeout;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }
}
