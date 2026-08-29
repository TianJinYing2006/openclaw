package com.wechatbot.fashion.ai.provider;

/**
 * 图片生成能力的供应商抽象。
 *
 * <p>当前只实现了百炼（DashScope），后续可按需添加 OpenAI DALL-E、Stable Diffusion 等
 * 供应商，只需新增一个 {@link ImageGenerationProvider} 实现类即可。</p>
 *
 * @see com.wechatbot.fashion.ai.provider.dashscope.DashScopeImageGenerationProvider
 */
public interface ImageGenerationProvider {

    /**
     * @param prompt 画面描述（不包含"生图："前缀）
     * @param size 图片尺寸，格式 "宽x高"，如 "1024x1024"
     * @param model 模型名称。传 null 或空时使用该供应商的默认模型
     * @return 生成结果
     */
    Result generate(String prompt, String size, String model);

    /** 使用默认模型生成图片。 */
    default Result generate(String prompt, String size) {
        return generate(prompt, size, null);
    }

    /** 供应商名称，用于日志和监控。 */
    String providerName();

    /**
     * 图片生成结果。成功时 {@link #imageBytes()} 有值，失败时 {@link #errorMessage()} 有值。
     */
    record Result(byte[] imageBytes, String errorMessage, boolean success) {

        public static Result image(byte[] bytes) {
            return new Result(bytes.clone(), null, true);
        }

        public static Result error(String message) {
            return new Result(null, message, false);
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes == null ? null : imageBytes.clone();
        }
    }
}
