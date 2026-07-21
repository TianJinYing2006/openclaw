package com.example.ykdsummer.bot.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegVideoFrameExtractorTest {

    @Test
    void choosesThreeToTenFramesFromDuration() {
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(2, 3, 10, 3)).isEqualTo(3);
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(12, 3, 10, 3)).isEqualTo(4);
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(60, 3, 10, 3)).isEqualTo(10);
    }

    @Test
    void fixedTenFrameConfigurationAlwaysChoosesTenFrames() {
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(2, 10, 10, 3)).isEqualTo(10);
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(12, 10, 10, 3)).isEqualTo(10);
        assertThat(FfmpegVideoFrameExtractor.calculateFrameCount(60, 10, 10, 3)).isEqualTo(10);
    }

    @Test
    void placesFramesAtTheMiddleOfEqualTimeSegments() {
        assertThat(FfmpegVideoFrameExtractor.calculateTimestamps(12, 4))
                .containsExactly(1_500L, 4_500L, 7_500L, 10_500L);
    }
}
