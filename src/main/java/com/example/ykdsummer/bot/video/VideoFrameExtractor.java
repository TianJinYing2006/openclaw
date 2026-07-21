package com.example.ykdsummer.bot.video;

import java.util.List;

/** 把一个已经解密的视频转换成按时间排序的静态帧。 */
public interface VideoFrameExtractor {

    List<VideoFrame> extract(byte[] videoBytes);
}
