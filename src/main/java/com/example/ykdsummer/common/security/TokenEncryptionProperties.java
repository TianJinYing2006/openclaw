package com.example.ykdsummer.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 敏感令牌（如 iLink contextToken）落库加密配置。
 *
 * <p>开发期可不配置：此时 {@link TokenCipher} 降级为明文透传并记录告警，
 * 保证现有部署不因缺 key 而中断；配置 32 字节 Base64 密钥后自动启用 AES-GCM 加密。</p>
 */
@ConfigurationProperties("app.security")
public class TokenEncryptionProperties {

    /** AES-256-GCM 主密钥（32 字节随机值的 Base64）；空表示暂不加密。 */
    private String tokenEncryptionKey = "";

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey == null ? "" : tokenEncryptionKey.strip();
    }
}
