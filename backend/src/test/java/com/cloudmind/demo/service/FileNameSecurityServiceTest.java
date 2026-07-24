package com.cloudmind.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileNameSecurityServiceTest {
    private final FileNameSecurityService service = new FileNameSecurityService();

    @Test
    void normalizesPathSeparatorsAndTrailingDots() {
        assertEquals("reports_2026_final.txt", service.sanitize(" reports/2026\\final.txt. "));
    }

    @Test
    void rejectsReservedNamesAndInvisibleControls() {
        assertThrows(IllegalArgumentException.class, () -> service.sanitize("CON.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.sanitize("safe\u202Etxt.exe"));
        assertThrows(IllegalArgumentException.class, () -> service.sanitize("line\nbreak.txt"));
    }

    @Test
    void rejectsDotPathsAndExcessivelyLongNames() {
        assertThrows(IllegalArgumentException.class, () -> service.sanitize(".."));
        assertThrows(IllegalArgumentException.class, () -> service.sanitize("a".repeat(181)));
    }
}
