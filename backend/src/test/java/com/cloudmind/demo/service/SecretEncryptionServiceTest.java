package com.cloudmind.demo.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretEncryptionServiceTest {
    @Test
    void encryptsAndDecryptsApiKeyWithAuthenticatedEncryption() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        SecretEncryptionService service = new SecretEncryptionService(
                Base64.getEncoder().encodeToString(key)
        );

        String encrypted = service.encrypt("unit-test-production-secret");

        assertTrue(service.isEncrypted(encrypted));
        assertFalse(encrypted.contains("unit-test-production-secret"));
        assertEquals("unit-test-production-secret", service.decrypt(encrypted));
    }

    @Test
    void refusesToStoreSecretWithoutEncryptionKey() {
        SecretEncryptionService service = new SecretEncryptionService("");

        assertFalse(service.isConfigured());
        assertThrows(
                IllegalStateException.class,
                () -> service.encrypt("unit-test-must-not-be-plaintext")
        );
    }

    @Test
    void rejectsWrongLengthEncryptionKey() {
        String shortKey = Base64.getEncoder().encodeToString(
                "too-short".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(
                IllegalStateException.class,
                () -> new SecretEncryptionService(shortKey)
        );
    }
}
