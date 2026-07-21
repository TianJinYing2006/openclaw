package com.example.ykdsummer.bot.audio;

import java.util.Optional;

/** 把最终文字答案合成为可作为微信附件发送的音频。 */
public interface TextToSpeechService {

    /**
     * @return 服务关闭或尚未配置时返回 empty；调用失败或文本不合法时抛出 SpeechSynthesisException。
     */
    Optional<SynthesizedAudio> synthesize(String text, String modelId, String voiceId);

    /** 使用应用默认模型并指定音色。 */
    default Optional<SynthesizedAudio> synthesize(String text, String voiceId) {
        return synthesize(text, null, voiceId);
    }

    /** 使用应用默认音色，主要供不需要按用户选音色的调用方使用。 */
    default Optional<SynthesizedAudio> synthesize(String text) {
        return synthesize(text, null, null);
    }

    /** 合成后的音频字节及文件名；第一版固定使用 MP3 文件，不承诺微信原生语音气泡。 */
    record SynthesizedAudio(String fileName, byte[] bytes) {
        public SynthesizedAudio {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
