package com.wechatbot.fashion.bot.video;

import com.wechatbot.fashion.ai.model.AiImage;
import com.wechatbot.fashion.ai.service.AiChatService;
import com.wechatbot.fashion.bot.audio.AudioTrackExtractor;
import com.wechatbot.fashion.bot.audio.AudioTranscriptionService;
import com.wechatbot.fashion.bot.config.VideoProcessingProperties;
import io.github.morningwn.protocol.MessageItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoAnalysisServiceTest {

    @Test
    void sendsOrderedLowDetailFramesAndTimestampPromptToChatService() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = mock(ILinkVideoDownloader.class);
        VideoFrameExtractor extractor = mock(VideoFrameExtractor.class);
        AudioTrackExtractor audioExtractor = mock(AudioTrackExtractor.class);
        AudioTranscriptionService transcriptionService = mock(AudioTranscriptionService.class);
        AiChatService chatService = mock(AiChatService.class);
        List<MessageItem> items = List.of();
        byte[] video = {1, 2, 3};
        AiImage first = new AiImage("image/jpeg", new byte[]{4}, AiImage.Detail.LOW);
        AiImage second = new AiImage("image/jpeg", new byte[]{5}, AiImage.Detail.LOW);
        when(downloader.downloadVideo(items)).thenReturn(video);
        when(extractor.extract(video)).thenReturn(List.of(
                new VideoFrame(1_500, first),
                new VideoFrame(4_500, second)
        ));
        byte[] wav = {6, 7, 8};
        when(audioExtractor.extract(video)).thenReturn(Optional.of(wav));
        when(transcriptionService.transcribe(wav)).thenReturn(Optional.of("视频里的人说：测试成功"));
        when(chatService.answer(eq("user"), org.mockito.ArgumentMatchers.anyString(), eq(List.of(first, second))))
                .thenReturn("模型视频回答");
        VideoAnalysisService service = new VideoAnalysisService(
                properties, downloader, extractor, audioExtractor, transcriptionService, chatService);

        String result = service.analyze("user", "发生了什么", items);

        assertThat(result).isEqualTo("模型视频回答");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).answer(eq("user"), prompt.capture(), eq(List.of(first, second)));
        assertThat(prompt.getValue()).contains(
                "发生了什么",
                "第1帧=1.5秒",
                "第2帧=4.5秒",
                "视频里的人说：测试成功",
                "请结合全部画面和音频转写回答"
        );
    }

    @Test
    void returnsSafeMessageWhenVideoDownloadFails() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = mock(ILinkVideoDownloader.class);
        when(downloader.downloadVideo(List.of())).thenThrow(new VideoProcessingException("视频读取失败，请重新发送"));
        VideoAnalysisService service = new VideoAnalysisService(
                properties,
                downloader,
                mock(VideoFrameExtractor.class),
                mock(AudioTrackExtractor.class),
                mock(AudioTranscriptionService.class),
                mock(AiChatService.class)
        );

        assertThat(service.analyze("user", "", List.of())).isEqualTo("视频读取失败，请重新发送");
    }

    @Test
    void fallsBackToFramesWhenAudioTranscriptionFails() {
        VideoProcessingProperties properties = new VideoProcessingProperties();
        ILinkVideoDownloader downloader = mock(ILinkVideoDownloader.class);
        VideoFrameExtractor extractor = mock(VideoFrameExtractor.class);
        AudioTrackExtractor audioExtractor = mock(AudioTrackExtractor.class);
        AudioTranscriptionService transcriptionService = mock(AudioTranscriptionService.class);
        AiChatService chatService = mock(AiChatService.class);
        byte[] video = {1};
        byte[] wav = {2};
        AiImage frameImage = new AiImage("image/jpeg", new byte[]{3}, AiImage.Detail.LOW);
        when(downloader.downloadVideo(List.of())).thenReturn(video);
        when(extractor.extract(video)).thenReturn(List.of(new VideoFrame(500, frameImage)));
        when(audioExtractor.extract(video)).thenReturn(Optional.of(wav));
        when(transcriptionService.transcribe(wav)).thenThrow(new IllegalStateException("temporary ASR error"));
        when(chatService.answer(eq("user"), org.mockito.ArgumentMatchers.anyString(), eq(List.of(frameImage))))
                .thenReturn("仅根据画面回答");
        VideoAnalysisService service = new VideoAnalysisService(
                properties, downloader, extractor, audioExtractor, transcriptionService, chatService);

        assertThat(service.analyze("user", "看看视频", List.of())).isEqualTo("仅根据画面回答");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).answer(eq("user"), prompt.capture(), eq(List.of(frameImage)));
        assertThat(prompt.getValue()).contains("没有检测到可用的音频转写");
    }
}
