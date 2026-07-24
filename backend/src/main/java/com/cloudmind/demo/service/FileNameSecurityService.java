package com.cloudmind.demo.service;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

@Service
public class FileNameSecurityService {
    private static final int MAX_CODE_POINTS = 180;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public String sanitize(String rawName) {
        if (rawName == null) throw new IllegalArgumentException("名称不能为空");
        String normalized = Normalizer.normalize(rawName, Normalizer.Form.NFKC);
        rejectInvisibleControls(normalized);

        String name = normalized
                .replace('\\', '_')
                .replace('/', '_')
                .strip()
                .replaceAll("[. ]+$", "");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("名称不能为空或使用相对路径名称");
        }
        if (name.codePointCount(0, name.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("名称不能超过 180 个字符");
        }

        String baseName = name;
        int dot = baseName.indexOf('.');
        if (dot >= 0) baseName = baseName.substring(0, dot);
        if (WINDOWS_RESERVED.contains(baseName.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("名称不能使用系统保留名");
        }
        return name;
    }

    private void rejectInvisibleControls(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || isBidiControl(codePoint)
                    || codePoint == 0x200B
                    || codePoint == 0xFEFF) {
                throw new IllegalArgumentException("名称不能包含控制字符或不可见方向字符");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private boolean isBidiControl(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069)
                || codePoint == 0x061C
                || codePoint == 0x200E
                || codePoint == 0x200F;
    }
}
