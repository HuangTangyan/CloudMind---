package com.cloudmind.demo.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityValidatorTest {
    @Test
    void productionAllowsExplicitHttpsOrigins() {
        assertDoesNotThrow(() -> new ProductionSecurityValidator(
                "https://cloudmind.example.com,https://admin.example.com"
        ));
    }

    @Test
    void productionRejectsHttpOrigins() {
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityValidator("http://cloudmind.example.com")
        );
    }

    @Test
    void corsRejectsWildcardOrigins() {
        assertThrows(IllegalStateException.class, () -> new CorsConfig("*"));
        assertThrows(
                IllegalStateException.class,
                () -> new CorsConfig("https://*.example.com")
        );
    }

    @Test
    void corsRejectsOriginsWithCredentialsQueryOrFragment() {
        assertThrows(
                IllegalStateException.class,
                () -> new CorsConfig("https://user@example.com")
        );
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityValidator("https://example.com?source=unsafe")
        );
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSecurityValidator("https://example.com#unsafe")
        );
    }
}
