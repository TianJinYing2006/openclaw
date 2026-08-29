package com.example.ykdsummer.admin.service;

import com.example.ykdsummer.admin.config.AdminWebProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Encrypts iLink login state before it is persisted. The master key never enters MySQL. */
@Component
public class SessionCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AdminWebProperties properties;

    public SessionCipher(AdminWebProperties properties) {
        this.properties = properties;
    }

    public EncryptedValue encrypt(String plainText) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(safe(plainText).getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt iLink session", exception);
        }
    }

    public String decrypt(String ciphertext, String iv) {
        if (ciphertext == null || ciphertext.isBlank() || iv == null || iv.isBlank()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(iv)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot decrypt iLink session. Check app.admin.session-encryption-key.", exception);
        }
    }

    private SecretKeySpec key() {
        String configured = properties.getSessionEncryptionKey();
        if (configured == null || configured.isBlank() || configured.startsWith("replace-with-")) {
            throw new IllegalStateException("app.admin.session-encryption-key is required for managed iLink sessions");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("app.admin.session-encryption-key must be Base64", exception);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("app.admin.session-encryption-key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public record EncryptedValue(String ciphertext, String iv) { }
}
