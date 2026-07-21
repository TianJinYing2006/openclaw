package com.example.ykdsummer.bot.audio;

import java.util.Optional;

/** 把 WAV 音频转成文字；服务关闭、未配置或没有有效语音时返回 empty。 */
public interface AudioTranscriptionService {

    Optional<String> transcribe(byte[] wavBytes);
}
