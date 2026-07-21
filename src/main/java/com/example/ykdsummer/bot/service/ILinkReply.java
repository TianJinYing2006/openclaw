package com.example.ykdsummer.bot.service;

/**
 * 本项目内部的统一回复结果。{@link ILinkReplyService} 只负责产生这个结果，
 * {@link ILinkBotService} 再根据实际类型选择 SDK 的 replyText 或 sendImage。
 */
public sealed interface ILinkReply permits ILinkReply.Text, ILinkReply.Image, ILinkReply.AudioFile,
        ILinkReply.DocumentFile {
    /** 最终通过 iLink sendmessage 发送的文字。 */
    record Text(String value) implements ILinkReply { }
    /**
     * 最终交给 iLink SDK 上传的原始图片字节。构造和读取都复制数组，防止外部代码修改内容。
     */
    record Image(byte[] bytes) implements ILinkReply {
        public Image { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /**
     * 语音模式只把 MP3 作为文件附件发送，不额外发送文字答案。
     * 第一版不承诺微信原生语音气泡，避免依赖未经真机验证的 SILK 编码链路。
     */
    record AudioFile(String fileName, byte[] bytes) implements ILinkReply {
        public AudioFile { bytes = bytes == null ? new byte[0] : bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** 文件生成链先发送文件，再发送简短的 followUpText 结果说明。 */
    record DocumentFile(String fileName, byte[] bytes, String followUpText) implements ILinkReply {
        public DocumentFile { bytes = bytes == null ? new byte[0] : bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
