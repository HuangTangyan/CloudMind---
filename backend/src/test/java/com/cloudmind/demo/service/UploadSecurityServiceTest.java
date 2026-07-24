package com.cloudmind.demo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadSecurityServiceTest {
    private UploadSecurityService service;

    @BeforeEach
    void setUp() {
        service = new UploadSecurityService();
        ReflectionTestUtils.setField(service, "maxFileBytes", 1024L);
    }

    @Test
    void acceptsTextWhenExtensionAndDetectedMimeMatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "application/octet-stream",
                "CloudMind security notes".getBytes()
        );

        UploadSecurityService.UploadInspection inspection = service.inspect(file, "notes.txt");

        assertEquals("text/plain", inspection.contentType());
    }

    @Test
    void rejectsExecutableDisguisedAsPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report.pdf",
                "application/pdf",
                new byte[] {'M', 'Z', 0, 0, 0, 0, 0, 0}
        );

        assertThrows(IllegalArgumentException.class, () -> service.inspect(file, "report.pdf"));
    }

    @Test
    void rejectsActiveWebContentAndDangerousDoubleExtensions() {
        MockMultipartFile html = new MockMultipartFile(
                "file",
                "page.html",
                "text/html",
                "<script>alert(1)</script>".getBytes()
        );
        MockMultipartFile doubleExtension = new MockMultipartFile(
                "file",
                "payload.exe.txt",
                "text/plain",
                "not executable".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> service.inspect(html, "page.html"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.inspect(doubleExtension, "payload.exe.txt")
        );
    }

    @Test
    void enforcesServiceSideFileSizeLimit() {
        ReflectionTestUtils.setField(service, "maxFileBytes", 4L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "12345".getBytes()
        );

        assertThrows(IllegalArgumentException.class, () -> service.inspect(file, "notes.txt"));
    }
}
