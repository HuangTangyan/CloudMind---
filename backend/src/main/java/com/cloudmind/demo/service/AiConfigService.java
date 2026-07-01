package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AiConfig;
import com.cloudmind.demo.entity.CloudFile;
import com.cloudmind.demo.entity.FileKind;
import com.cloudmind.demo.repository.AiConfigRepository;
import com.cloudmind.demo.repository.CloudFileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class AiConfigService {
    private final AiConfigRepository configRepository;
    private final CloudFileRepository fileRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    public AiConfigService(AiConfigRepository configRepository,
                           CloudFileRepository fileRepository,
                           ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.fileRepository = fileRepository;
        this.objectMapper = objectMapper;
    }

    public AiConfig getOrCreate() {
        return configRepository.findById(1L).orElseGet(() -> {
            AiConfig config = new AiConfig();
            config.setId(1L);
            config.setReviewPrompt(defaultReviewPrompt());
            return configRepository.save(config);
        });
    }

    public Map<String, Object> getConfigForAdmin() {
        AiConfig config = getOrCreate();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", Boolean.TRUE.equals(config.getEnabled()));
        map.put("provider", config.getProvider());
        map.put("baseUrl", config.getBaseUrl());
        map.put("model", config.getModel());
        map.put("apiKey", "");
        map.put("hasApiKey", config.getApiKey() != null && !config.getApiKey().isBlank());
        map.put("reviewPrompt", config.getReviewPrompt() == null || config.getReviewPrompt().isBlank() ? defaultReviewPrompt() : config.getReviewPrompt());
        map.put("updatedAt", config.getUpdatedAt());
        return map;
    }

    @Transactional
    public Map<String, Object> saveConfig(Map<String, Object> body) {
        AiConfig config = getOrCreate();
        if (body.containsKey("enabled")) config.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        if (body.containsKey("provider")) config.setProvider(clean(String.valueOf(body.get("provider")), "DeepSeek / OpenAI 兼容"));
        if (body.containsKey("baseUrl")) config.setBaseUrl(clean(String.valueOf(body.get("baseUrl")), "https://api.deepseek.com/v1"));
        if (body.containsKey("model")) config.setModel(clean(String.valueOf(body.get("model")), "deepseek-chat"));
        if (body.containsKey("reviewPrompt")) config.setReviewPrompt(clean(String.valueOf(body.get("reviewPrompt")), defaultReviewPrompt()));
        if (body.containsKey("apiKey")) {
            String key = String.valueOf(body.getOrDefault("apiKey", "")).trim();
            if (!key.isBlank() && !"KEEP_EXISTING".equals(key)) config.setApiKey(key);
        }
        configRepository.save(config);
        return getConfigForAdmin();
    }

    public Map<String, Object> testConnection() {
        AiConfig config = getOrCreate();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return Map.of("ok", false, "message", "AI 审查未启用。请先勾选启用 AI 审查并保存配置。");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return Map.of("ok", false, "message", "API Key 为空。请填写 Key 后保存。");
        }
        try {
            String content = callChat(config, "只回复：连接成功", "这是 CloudMind 管理后台连接测试。", 600);
            return Map.of("ok", true, "message", content == null || content.isBlank() ? "AI 接口已返回响应。" : content);
        } catch (Exception e) {
            return Map.of("ok", false, "message", "连接失败：" + e.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> aiReviewFile(Long fileId) {
        CloudFile file = fileRepository.findById(fileId).orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        if (Boolean.TRUE.equals(file.getDeleted())) throw new IllegalArgumentException("回收站内文件暂不审查");
        if (file.getKind() != FileKind.FILE) throw new IllegalArgumentException("只能审查文件，不能审查文件夹");

        ReviewResult result = reviewByAiOrRule(file);
        applyReviewResult(file, result);
        fileRepository.save(file);
        return Map.of(
                "fileId", file.getId(),
                "name", file.getName(),
                "status", file.getReviewStatus(),
                "note", file.getReviewNote() == null ? "" : file.getReviewNote()
        );
    }

    @Transactional
    public Map<String, Object> aiReviewAll() {
        List<CloudFile> files = fileRepository.findAll().stream()
                .filter(f -> f.getKind() == FileKind.FILE)
                .filter(f -> !Boolean.TRUE.equals(f.getDeleted()))
                .toList();
        int normal = 0;
        int abnormal = 0;
        int pending = 0;
        for (CloudFile file : files) {
            ReviewResult result = reviewByAiOrRule(file);
            applyReviewResult(file, result);
            fileRepository.save(file);
            if ("ABNORMAL".equals(result.status())) abnormal++;
            else if ("PENDING".equals(result.status())) pending++;
            else normal++;
        }
        return Map.of("total", files.size(), "normal", normal, "abnormal", abnormal, "pending", pending);
    }


    @Transactional
    public Map<String, Object> aiReviewPending() {
        List<CloudFile> files = fileRepository.findAll().stream()
                .filter(f -> f.getKind() == FileKind.FILE)
                .filter(f -> !Boolean.TRUE.equals(f.getDeleted()))
                .filter(f -> f.getReviewStatus() == null || f.getReviewStatus().isBlank() || "PENDING".equalsIgnoreCase(f.getReviewStatus()))
                .toList();
        int normal = 0;
        int abnormal = 0;
        int pending = 0;
        for (CloudFile file : files) {
            ReviewResult result = reviewByAiOrRule(file);
            applyReviewResult(file, result);
            fileRepository.save(file);
            if ("ABNORMAL".equals(result.status())) abnormal++;
            else if ("PENDING".equals(result.status())) pending++;
            else normal++;
        }
        return Map.of("total", files.size(), "normal", normal, "abnormal", abnormal, "pending", pending);
    }

    private void applyReviewResult(CloudFile file, ReviewResult result) {
        file.setReviewStatus(result.status());
        file.setReviewNote(result.note());
        if (result.summary() != null && !result.summary().isBlank()) {
            file.setSummary(trimTo(result.summary(), 240));
        }
        if (result.tags() != null && !result.tags().isBlank()) {
            file.setTags(trimTo(result.tags(), 180));
        }
    }

    private ReviewResult reviewByAiOrRule(CloudFile file) {
        AiConfig config = getOrCreate();
        String content = fileContentForReview(file);
        if (!Boolean.TRUE.equals(config.getEnabled()) || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return localRuleReview(file, content);
        }
        try {
            String systemPrompt = config.getReviewPrompt() == null || config.getReviewPrompt().isBlank()
                    ? defaultReviewPrompt()
                    : config.getReviewPrompt();
            systemPrompt = systemPrompt + "\n\n无论上面的提示词如何，请最终严格按四行返回：status=NORMAL/ABNORMAL/PENDING；summary=80字以内中文摘要；tags=3到8个中文标签，用英文逗号分隔；note=简短审查原因。";
            String aiResponse = callChat(config, systemPrompt, content, 1800);
            String status = parseStatus(aiResponse);
            String summary = firstNonBlank(parseField(aiResponse, "summary"), parseField(aiResponse, "摘要"));
            String tags = firstNonBlank(parseField(aiResponse, "tags"), parseField(aiResponse, "标签"));
            String note = firstNonBlank(parseField(aiResponse, "note"), parseField(aiResponse, "原因"));
            if (note.isBlank()) note = "AI审查：" + Optional.ofNullable(aiResponse).orElse("无返回");
            return new ReviewResult(status, trimTo(note, 480), normalizeTags(tags), summary);
        } catch (Exception e) {
            return new ReviewResult("PENDING", trimTo("AI接口调用失败，等待人工审查：" + e.getMessage(), 480), null, null);
        }
    }

    private ReviewResult localRuleReview(CloudFile file, String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        List<String> riskyWords = List.of("赌博", "诈骗", "木马", "病毒", "涉黄", "暴恐", "违禁", "毒品", "枪支", "黑产", "hack", "malware", "trojan");
        for (String word : riskyWords) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return new ReviewResult("ABNORMAL", "本地规则命中疑似敏感词：" + word + "。建议人工复核。", null, null);
            }
        }
        return new ReviewResult("NORMAL", "本地规则未发现明显异常。未配置 AI Key 时使用本地规则审查。", null, null);
    }

    private String fileContentForReview(CloudFile file) {
        String text = Optional.ofNullable(file.getExtractedText()).orElse("");
        if (text.length() > 3500) text = text.substring(0, 3500);
        return "文件名：" + file.getName() + "\n"
                + "类型：" + Optional.ofNullable(file.getContentType()).orElse("未知") + "\n"
                + "摘要：" + Optional.ofNullable(file.getSummary()).orElse("") + "\n"
                + "标签：" + Optional.ofNullable(file.getTags()).orElse("") + "\n"
                + "正文片段：\n" + text;
    }

    private String callChat(AiConfig config, String systemPrompt, String userContent, int maxTokens) throws Exception {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("temperature", 0);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + "：" + trimTo(response.body(), 240));
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isMissingNode() ? response.body() : content.asText();
    }

    private String parseStatus(String aiResponse) {
        String explicit = firstNonBlank(parseField(aiResponse, "status"), parseField(aiResponse, "状态"));
        String text = (explicit.isBlank() ? Optional.ofNullable(aiResponse).orElse("") : explicit).toUpperCase(Locale.ROOT);
        if (text.contains("ABNORMAL") || text.contains("异常") || text.contains("违规") || text.contains("敏感")) return "ABNORMAL";
        if (text.contains("PENDING") || text.contains("不确定") || text.contains("人工")) return "PENDING";
        return "NORMAL";
    }

    private String parseField(String response, String key) {
        if (response == null || key == null || key.isBlank()) return "";
        for (String rawLine : response.split("\r?\n")) {
            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith(lowerKey + "=") || lower.startsWith(lowerKey + "：") || lower.startsWith(lowerKey + ":")) {
                int idx = Math.max(Math.max(line.indexOf('='), line.indexOf('：')), line.indexOf(':'));
                if (idx >= 0 && idx + 1 < line.length()) return line.substring(idx + 1).trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) return value.trim();
        }
        return "";
    }

    private String normalizeTags(String tags) {
        if (tags == null || tags.isBlank()) return null;
        return Arrays.stream(tags.replace('，', ',').replace('；', ',').replace(';', ',').split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(8)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String defaultReviewPrompt() {
        return "你是 CloudMind 网盘的内容安全审查与文档分析助手。请根据文件名、正文片段生成摘要和标签，并判断内容安全风险。请严格按四行返回：status=NORMAL/ABNORMAL/PENDING；summary=80字以内中文摘要；tags=3到8个中文标签，用英文逗号分隔；note=简短审查原因。不要输出其他内容。";
    }

    private String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private String trimTo(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record ReviewResult(String status, String note, String tags, String summary) {}
}
