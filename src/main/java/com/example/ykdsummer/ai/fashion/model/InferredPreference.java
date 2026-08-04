package com.example.ykdsummer.ai.fashion.model;

/**
 * 偏好抽取结果：从用户反馈中抽出的规范化偏好。
 *
 * @param dimension 维度（COLOR / STYLE / FIT / PATTERN / MATERIAL / CATEGORY，与排序引擎一致）
 * @param value     规范化值（如 BLACK、RELAXED、JEANS）
 * @param polarity  极性（POSITIVE 喜欢 / NEGATIVE 不喜欢）
 */
public record InferredPreference(String dimension, String value, String polarity) {

    public InferredPreference {
        dimension = normalize(dimension);
        value = normalize(value);
        polarity = polarity == null || polarity.isBlank() ? "POSITIVE" : normalize(polarity);
    }

    public boolean valid() {
        return !dimension.isBlank() && !value.isBlank();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
