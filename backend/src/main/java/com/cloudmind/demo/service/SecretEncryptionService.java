package com.cloudmind.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SecretEncryptionService {
    private static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretEncryptionService(
            @Value("${cloudmind.ai.config-encryption-key:}") String encodedKey
    ) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.secretKey = null;
            return;
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "CLOUDMIND_AI_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥"
            );
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "CLOUDMIND_AI_CONFIG_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥"
            );
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AI API Key 加密失败");
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        if (!isEncrypted(encrypted)) {
            throw new IllegalStateException("数据库中的 AI API Key 尚未加密，请重新保存");
        }
        requireConfigured();
        try {
            byte[] packed = Base64.getUrlDecoder().decode(encrypted.substring(PREFIX.length()));
            if (packed.length <= NONCE_BYTES) throw new GeneralSecurityException("invalid payload");
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[packed.length - NONCE_BYTES];
            System.arraycopy(packed, 0, nonce, 0, nonce.length);
            System.arraycopy(packed, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("数据库中的 AI API Key 无法解密，请重新配置");
        }
    }

    private void requireConfigured() {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "请先配置 CLOUDMIND_AI_CONFIG_ENCRYPTION_KEY 后再保存 AI API Key"
            );
        }
    }
}
