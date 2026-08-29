package com.wechatbot.fashion.bot.audio;

import java.util.Optional;

/** 从已解密的视频字节中提取适合语音识别的 WAV；无音轨时返回 empty。 */
public interface AudioTrackExtractor {

    Optional<byte[]> extract(byte[] videoBytes);
}
