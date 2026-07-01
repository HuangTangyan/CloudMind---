package com.cloudmind.demo.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TextAnalyzeService {
    private static final int MAX_PARSE_BYTES = 30 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 120_000;
    private final Tika tika = new Tika();

    public AnalysisResult analyze(InputStream inputStream, String fileName, String contentType, long sizeBytes) {
        if (inputStream == null || sizeBytes > MAX_PARSE_BYTES) {
            return new AnalysisResult("", "文件较大或暂不支持解析，已保存基础元数据。", tagsFromName(fileName));
        }
        try (InputStream in = inputStream) {
            String text = Optional.ofNullable(tika.parseToString(in)).orElse("");
            text = clean(text);
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            return new AnalysisResult(text, summarize(text), generateTags(fileName, text));
        } catch (Exception e) {
            return new AnalysisResult("", "暂未提取到可解析文本。", tagsFromName(fileName));
        }
    }

    public String clean(String text) {
        if (text == null) return "";
        return text.replace('\u0000', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    public String summarize(String text) {
        if (text == null || text.isBlank()) return "暂无可用摘要。";
        String value = text.trim().replaceAll("\\s+", " ");
        String[] parts = value.split("(?<=[。！？!?.])");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (sb.length() + part.length() > 180) break;
            sb.append(part.trim());
            if (sb.length() >= 80) break;
        }
        if (sb.isEmpty()) sb.append(value, 0, Math.min(value.length(), 160));
        return sb.toString();
    }

    public String generateTags(String fileName, String text) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tags.addAll(Arrays.asList(tagsFromName(fileName).split(",")));
        String source = ((fileName == null ? "" : fileName) + " " + (text == null ? "" : text)).toLowerCase();
        Map<String, String> dictionary = new LinkedHashMap<>();
        dictionary.put("数据库", "数据库");
        dictionary.put("mysql", "MySQL");
        dictionary.put("java", "Java");
        dictionary.put("spring", "Spring Boot");
        dictionary.put("vue", "Vue");
        dictionary.put("算法", "算法");
        dictionary.put("数据结构", "数据结构");
        dictionary.put("高等数学", "高等数学");
        dictionary.put("线性代数", "线性代数");
        dictionary.put("实验", "实验报告");
        dictionary.put("复习", "课程复习");
        dictionary.put("论文", "论文文献");
        dictionary.put("人工智能", "人工智能");
        dictionary.put("机器学习", "机器学习");
        dictionary.put("cloud", "云计算");
        dictionary.forEach((key, tag) -> { if (source.contains(key)) tags.add(tag); });
        return tags.stream().filter(s -> s != null && !s.isBlank()).limit(8).collect(Collectors.joining(","));
    }

    private String tagsFromName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "未分类";
        String lower = fileName.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (lower.endsWith(".pdf")) tags.add("PDF");
        else if (lower.endsWith(".doc") || lower.endsWith(".docx")) tags.add("Word");
        else if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) tags.add("PPT");
        else if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) tags.add("Excel");
        else if (lower.endsWith(".txt") || lower.endsWith(".md")) tags.add("文本资料");
        else if (Pattern.compile("\\.(png|jpg|jpeg|gif|webp)$").matcher(lower).find()) tags.add("图片");
        else if (Pattern.compile("\\.(mp4|mov|mkv|mp3|wav)$").matcher(lower).find()) tags.add("音视频");
        if (tags.isEmpty()) tags.add("未分类");
        return String.join(",", tags);
    }

    public record AnalysisResult(String extractedText, String summary, String tags) {}
}
