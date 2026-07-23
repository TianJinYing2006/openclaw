package com.example.ykdsummer.ai.model;

/** Agent 工具在一轮请求中产出的非文字结果，例如可直接回传微信的图片。 */
public record AiArtifact(Type type, byte[] bytes, String fileName, String description, String assetId, int version) {
    public AiArtifact {
        bytes = bytes == null ? null : bytes.clone();
    }

    public static AiArtifact image(byte[] bytes, String description, String assetId, int version) {
        return new AiArtifact(Type.IMAGE, bytes, "image.png", description, assetId, version);
    }

    public static AiArtifact audio(byte[] bytes, String fileName, String description) {
        return new AiArtifact(Type.AUDIO, bytes, fileName, description, null, 0);
    }

    public static AiArtifact document(byte[] bytes, String fileName, String description, String assetId, int version) {
        return new AiArtifact(Type.DOCUMENT, bytes, fileName, description, assetId, version);
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    public enum Type { IMAGE, AUDIO, DOCUMENT }
}
