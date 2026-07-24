package com.cloudmind.demo.service;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UploadSecurityService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf",
            "txt", "md", "csv", "json", "xml", "yml", "yaml",
            "java", "py", "js", "ts", "css", "sql", "properties",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "gif", "webp",
            "mp4", "webm", "mov", "mp3", "wav", "ogg"
    );

    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "dll", "com", "bat", "cmd", "ps1", "sh", "scr", "msi",
            "jar", "war", "php", "asp", "aspx", "jsp", "hta", "vbs",
            "html", "htm", "svg", "mjs", "apk", "iso", "img"
    );

    private static final Map<String, Set<String>> EXACT_MIME_TYPES = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("doc", Set.of("application/msword", "application/x-tika-msoffice")),
            Map.entry("docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-ooxml",
                    "application/zip"
            )),
            Map.entry("xls", Set.of("application/vnd.ms-excel", "application/x-tika-msoffice")),
            Map.entry("xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-tika-ooxml",
                    "application/zip"
            )),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint", "application/x-tika-msoffice")),
            Map.entry("pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/x-tika-ooxml",
                    "application/zip"
            )),
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("mp4", Set.of("video/mp4", "application/mp4")),
            Map.entry("webm", Set.of("video/webm")),
            Map.entry("mov", Set.of("video/quicktime")),
            Map.entry("mp3", Set.of("audio/mpeg")),
            Map.entry("wav", Set.of("audio/wav", "audio/x-wav")),
            Map.entry("ogg", Set.of("audio/ogg", "video/ogg", "application/ogg"))
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "xml", "yml", "yaml",
            "java", "py", "js", "ts", "css", "sql", "properties"
    );

    private final Tika tika = new Tika();

    @Value("${cloudmind.upload.max-file-bytes:104857600}")
    private long maxFileBytes = 100L * 1024L * 1024L;

    public UploadInspection inspect(MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() <= 0 || file.getSize() > maxFileBytes) {
            throw new IllegalArgumentException("单个文件不能超过 " + readableLimit());
        }
        if (fileName == null || fileName.isBlank() || fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("文件名无效");
        }

        String extension = extension(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不允许上传该文件类型：" + extensionLabel(extension));
        }
        rejectDangerousDoubleExtension(fileName, extension);

        String detectedType;
        try (InputStream inputStream = file.getInputStream()) {
            detectedType = normalizeMime(tika.detect(inputStream, fileName));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法识别文件实际类型，请确认文件未损坏");
        }

        if (isExecutableMime(detectedType) || !mimeMatches(extension, detectedType)) {
            throw new IllegalArgumentException("文件扩展名与实际内容类型不一致");
        }
        return new UploadInspection(fileName, detectedType);
    }

    private boolean mimeMatches(String extension, String detectedType) {
        if (TEXT_EXTENSIONS.contains(extension)) {
            return detectedType.startsWith("text/")
                    || Set.of(
                            "application/json",
                            "application/xml",
                            "application/x-yaml",
                            "application/yaml"
                    ).contains(detectedType);
        }
        return EXACT_MIME_TYPES.getOrDefault(extension, Set.of()).contains(detectedType);
    }

    private void rejectDangerousDoubleExtension(String fileName, String finalExtension) {
        String[] parts = fileName.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length < 3) return;
        boolean containsDangerousPart = Arrays.stream(parts, 1, parts.length - 1)
                .anyMatch(DANGEROUS_EXTENSIONS::contains);
        if (containsDangerousPart || DANGEROUS_EXTENSIONS.contains(finalExtension)) {
            throw new IllegalArgumentException("文件名包含危险的双扩展名");
        }
    }

    private boolean isExecutableMime(String contentType) {
        return contentType.contains("executable")
                || contentType.contains("x-dosexec")
                || contentType.contains("x-msdownload")
                || contentType.contains("sharedlib")
                || contentType.contains("java-archive")
                || contentType.contains("x-sh");
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw new IllegalArgumentException("文件必须包含允许的扩展名");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeMime(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private String extensionLabel(String extension) {
        return extension == null || extension.isBlank() ? "无扩展名" : "." + extension;
    }

    private String readableLimit() {
        long megabytes = Math.max(1L, maxFileBytes / 1024L / 1024L);
        return megabytes + "MB";
    }

    public record UploadInspection(String fileName, String contentType) {}
}
