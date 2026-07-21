package com.example.ykdsummer.bot.audio;

import com.example.ykdsummer.bot.config.TencentAsrProperties;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionRequest;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TencentCloudAsrServiceTest {

    @Test
    void doesNotCallCloudWhenCredentialsAreMissing() {
        TencentAsrProperties properties = new TencentAsrProperties();
        TencentCloudAsrService service = new TencentCloudAsrService(properties);

        assertThat(service.transcribe(new byte[]{1, 2, 3})).isEmpty();
    }

    @Test
    void buildsDirectWavUploadRequest() {
        TencentAsrProperties properties = new TencentAsrProperties();
        byte[] wav = {1, 2, 3, 4};

        SentenceRecognitionRequest request = TencentCloudAsrService.buildRequest(wav, properties);

        assertThat(request.getEngSerViceType()).isEqualTo("16k_zh");
        assertThat(request.getSourceType()).isEqualTo(1L);
        assertThat(request.getVoiceFormat()).isEqualTo("wav");
        assertThat(request.getDataLen()).isEqualTo(4L);
        assertThat(Base64.getDecoder().decode(request.getData())).containsExactly(wav);
    }
}
