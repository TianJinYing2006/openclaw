package com.wechatbot.fashion.common.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 敏感令牌（如 iLink contextToken）的 AES-256-GCM 加密工具。
 *
 * <p>密文格式：{@code enc:Base64(iv).Base64(cipher)}。未配置密钥时降级为明文透传并
 * 打一次告警，兼容开发期无 key 的部署；已加密的数据带 {@code enc:} 前缀，读取时自动识别。</p>
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final String PREFIX = "enc:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private final TokenEncryptionProperties properties;

    public TokenCipher(TokenEncryptionProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plainText) {
        String safe = plainText == null ? "" : plainText;
        SecretKeySpec key = key();
        if (key == null) {
            warnPlaintext("encrypt");
            return safe;
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(safe.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt token; check app.security.token-encryption-key", exception);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // 旧数据或降级明文
        }
        SecretKeySpec key = key();
        if (key == null) {
            warnPlaintext("decrypt");
            return stored;
        }
        try {
            String body = stored.substring(PREFIX.length());
            int dot = body.indexOf('.');
            if (dot <= 0) {
                return stored;
            }
            byte[] iv = Base64.getDecoder().decode(body.substring(0, dot));
            byte[] cipher = Base64.getDecoder().decode(body.substring(dot + 1));
            Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(decrypt.doFinal(cipher), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot decrypt token; check app.security.token-encryption-key", exception);
        }
    }

    /** 字节版加密（图片等二进制资源）；无密钥时透传明文并告警一次。 */
    public byte[] encryptBytes(byte[] plain) {
        if (plain == null || plain.length == 0) {
            return plain;
        }
        SecretKeySpec key = key();
        if (key == null) {
            warnPlaintext("encrypt-bytes");
            return plain;
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain);
            String text = PREFIX + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(encrypted);
            return text.getBytes(StandardCharsets.US_ASCII);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt bytes; check app.security.token-encryption-key", exception);
        }
    }

    /** 字节版解密：无 enc: 前缀视为旧明文原样返回；有前缀但无密钥时透传并告警。 */
    public byte[] decryptBytes(byte[] stored) {
        if (stored == null || stored.length == 0 || !startsWith(stored, PREFIX)) {
            return stored;
        }
        SecretKeySpec key = key();
        if (key == null) {
            warnPlaintext("decrypt-bytes");
            return stored;
        }
        try {
            String text = new String(stored, StandardCharsets.US_ASCII);
            String body = text.substring(PREFIX.length());
            int dot = body.indexOf('.');
            if (dot <= 0) {
                return stored;
            }
            byte[] iv = Base64.getDecoder().decode(body.substring(0, dot));
            byte[] cipher = Base64.getDecoder().decode(body.substring(dot + 1));
            Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return decrypt.doFinal(cipher);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot decrypt bytes; check app.security.token-encryption-key", exception);
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (data[i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    /** 返回 null 表示未配置密钥（调用方降级明文）。 */
    private SecretKeySpec key() {
        String configured = properties.getTokenEncryptionKey();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(configured);
            if (raw.length != 32) {
                throw new IllegalStateException("app.security.token-encryption-key must decode to exactly 32 bytes");
            }
            return new SecretKeySpec(raw, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("app.security.token-encryption-key must be Base64", exception);
        }
    }

    private static void warnPlaintext(String operation) {
        if (WARNED.compareAndSet(false, true)) {
            log.warn("TokenCipher {}: app.security.token-encryption-key is not configured, "
                    + "tokens are stored in plaintext. Generate with: "
                    + "[Convert]::ToBase64String((1..32 | %% { Get-Random -Maximum 256 }))", operation);
        }
    }
}
