package com.wechatbot.fashion.bot.audio;

import com.wechatbot.fashion.bot.config.TencentAsrProperties;
import com.tencentcloudapi.asr.v20190614.AsrClient;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionRequest;
import com.tencentcloudapi.asr.v20190614.models.SentenceRecognitionResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Optional;

/** 使用腾讯云官方 Java SDK 调用 SentenceRecognition，将本地 WAV 转成文字。 */
@Service
public class TencentCloudAsrService implements AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TencentCloudAsrService.class);

    private final TencentAsrProperties properties;

    public TencentCloudAsrService(TencentAsrProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> transcribe(byte[] wavBytes) {
        if (!properties.isEnabled() || !properties.isConfigured()) {
            return Optional.empty();
        }
        if (wavBytes == null || wavBytes.length == 0) {
            return Optional.empty();
        }
        if (wavBytes.length > properties.getMaxAudioSize().toBytes()) {
            throw new AudioTranscriptionException("audio exceeds SentenceRecognition limit");
        }

        try {
            SentenceRecognitionRequest request = buildRequest(wavBytes, properties);
            SentenceRecognitionResponse response = createClient(properties).SentenceRecognition(request);
            String result = response == null ? null : response.getResult();
            log.info(
                    "Tencent ASR completed, durationMs={}, requestIdPresent={}",
                    response == null ? null : response.getAudioDuration(),
                    response != null && response.getRequestId() != null
            );
            return result == null || result.isBlank() ? Optional.empty() : Optional.of(result.trim());
        } catch (TencentCloudSDKException exception) {
            throw new AudioTranscriptionException("Tencent ASR request failed", exception);
        } catch (RuntimeException exception) {
            if (exception instanceof AudioTranscriptionException transcriptionException) {
                throw transcriptionException;
            }
            throw new AudioTranscriptionException("Tencent ASR client failed", exception);
        }
    }

    static SentenceRecognitionRequest buildRequest(byte[] wavBytes, TencentAsrProperties properties) {
        SentenceRecognitionRequest request = new SentenceRecognitionRequest();
        request.setEngSerViceType(properties.getEngineServiceType());
        request.setSourceType(1L);
        request.setVoiceFormat(properties.getVoiceFormat());
        request.setData(Base64.getEncoder().encodeToString(wavBytes));
        request.setDataLen((long) wavBytes.length);
        return request;
    }

    private static AsrClient createClient(TencentAsrProperties properties) {
        int timeoutSeconds = Math.max(1, Math.toIntExact(properties.getTimeout().toSeconds()));
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(properties.getEndpoint());
        httpProfile.setConnTimeout(timeoutSeconds);
        httpProfile.setReadTimeout(timeoutSeconds);

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
        return new AsrClient(credential, properties.getRegion(), clientProfile);
    }
}
