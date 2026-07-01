package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AiConfig;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.entity.CloudFile;
import com.cloudmind.demo.entity.FileKind;
import com.cloudmind.demo.repository.AiConfigRepository;
import com.cloudmind.demo.repository.CloudFileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeQaService {
    private static final int MAX_CONTEXT_CHARS = 12000;
    private final CloudFileRepository fileRepository;
    private final AiConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    public KnowledgeQaService(CloudFileRepository fileRepository,
                              AiConfigRepository configRepository,
                              ObjectMapper objectMapper) {
        this.fileRepository = fileRepository;
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> ask(AppUser user, String rawQuestion, Integer rawTopK) {
        return ask(user, rawQuestion, rawTopK, "ALL", null, null);
    }

    public Map<String, Object> ask(AppUser user, String rawQuestion, Integer rawTopK, String rawScopeType, Long scopeId) {
        return ask(user, rawQuestion, rawTopK, rawScopeType, scopeId, null);
    }

    public Map<String, Object> ask(AppUser user, String rawQuestion, Integer rawTopK, String rawScopeType, Long scopeId, List<Long> scopeIds) {
        String question = cleanQuestion(rawQuestion);
        int topK = normalizeTopK(rawTopK);
        ScopeSelection scope = resolveScope(user, rawScopeType, scopeId, scopeIds);
        Set<String> detectedTerms = keywords(question);
        Set<String> questionTerms = detectedTerms.isEmpty()
                ? Set.of(question.toLowerCase(Locale.ROOT))
                : detectedTerms;
        boolean overviewIntent = isOverviewQuestion(question);

        List<ScoredFile> ranked = scope.files().stream()
                .filter(this::hasKnowledgeText)
                .map(file -> score(file, question, questionTerms))
                .filter(scored -> overviewIntent || scored.score() > 0)
                .sorted(scoredComparator(overviewIntent))
                .limit(topK)
                .toList();

        if (ranked.isEmpty()) {
            return Map.of(
                    "answer", "我暂时没有在" + scope.label() + "里找到可用于问答的文本内容。可以先上传或选择 PDF、Word、Markdown、TXT 等文本型文件，系统提取正文后再问。",
                    "usedAi", false,
                    "sources", List.of(),
                    "suggestions", List.of("换一个更具体的关键词再问", "选择一个包含文字内容的文件夹", "先在文件预览里确认资料是否已被系统提取正文")
            );
        }

        String context = buildContext(ranked, questionTerms);
        AiConfig config = configRepository.findById(1L).orElse(null);
        boolean aiReady = isAiReady(config);

        String answer;
        boolean usedAi = false;
        if (aiReady) {
            try {
                answer = callChat(config, systemPrompt(), "当前检索范围：" + scope.label() + "\n用户问题：" + question + "\n\n知识库片段：\n" + context, 2400);
                usedAi = true;
            } catch (Exception e) {
                answer = localAnswer(question, ranked, scope, questionTerms)
                        + "\n\n提示：AI 接口调用失败，已切换为本地知识库摘录回答。原因：" + trimTo(e.getMessage(), 160);
            }
        } else {
            answer = localAnswer(question, ranked, scope, questionTerms)
                    + "\n\n提示：当前未启用 AI 或未保存 API Key，系统使用本地检索摘录回答；管理员在“AI 接口配置”里配置后可生成更自然的答案。";
        }

        return Map.of(
                "answer", trimTo(answer, 5000),
                "usedAi", usedAi,
                "scope", scopeMap(scope),
                "sources", ranked.stream().map(this::sourceMap).toList(),
                "suggestions", buildSuggestions(ranked)
        );
    }

    public Map<String, Object> overview(AppUser user, Integer rawTopK, String rawScopeType, Long scopeId) {
        return overview(user, rawTopK, rawScopeType, scopeId, null);
    }

    public Map<String, Object> overview(AppUser user, Integer rawTopK, String rawScopeType, Long scopeId, List<Long> scopeIds) {
        int topK = normalizeOverviewTopK(rawTopK);
        ScopeSelection scope = resolveScope(user, rawScopeType, scopeId, scopeIds);
        List<ScoredFile> selected = scope.files().stream()
                .filter(this::hasKnowledgeText)
                .sorted(Comparator.comparing((CloudFile file) -> file.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .map(file -> new ScoredFile(file, 1, overviewSnippet(file)))
                .toList();

        if (selected.isEmpty()) {
            return Map.of(
                    "answer", scope.label() + "里暂时没有可生成概述的文本内容。建议选择包含摘要、标签或正文提取结果的文件。",
                    "usedAi", false,
                    "scope", scopeMap(scope),
                    "sources", List.of(),
                    "suggestions", List.of("换一个文件夹生成概述", "上传 PDF、Word、Markdown 或 TXT 后再试", "先对文件执行摘要/标签分析")
            );
        }

        String context = buildContext(selected, Set.of());
        AiConfig config = configRepository.findById(1L).orElse(null);
        boolean aiReady = isAiReady(config);
        String answer;
        boolean usedAi = false;
        if (aiReady) {
            try {
                String prompt = "请为" + scope.label() + "生成一份结构化概述。要求："
                        + "1. 先用一段话概括整体内容；2. 提炼3-6个关键主题；"
                        + "3. 给出适合继续追问的问题；4. 只依据给定资料，不要编造。\n\n资料：\n" + context;
                answer = callChat(config, overviewPrompt(), prompt, 2600);
                usedAi = true;
            } catch (Exception e) {
                answer = localOverview(scope, selected)
                        + "\n\n提示：AI 接口调用失败，已切换为本地概述。原因：" + trimTo(e.getMessage(), 160);
            }
        } else {
            answer = localOverview(scope, selected)
                    + "\n\n提示：当前未启用 AI 或未保存 API Key，系统使用本地摘要拼接生成概述；管理员配置 AI 后可得到更完整的综合概述。";
        }

        return Map.of(
                "answer", trimTo(answer, 5000),
                "usedAi", usedAi,
                "scope", scopeMap(scope),
                "sources", selected.stream().map(this::sourceMap).toList(),
                "suggestions", List.of("继续追问这个范围的重点", "帮我生成复习提纲", "帮我整理成项目介绍")
        );
    }

    public Map<String, Object> sources(AppUser user) {
        List<CloudFile> all = fileRepository.findByOwnerIdAndDeletedFalseOrderByKindAscNameAsc(user.getId());
        Map<Long, CloudFile> byId = all.stream()
                .filter(file -> file.getId() != null)
                .collect(Collectors.toMap(CloudFile::getId, file -> file, (a, b) -> a, LinkedHashMap::new));

        List<Map<String, Object>> files = all.stream()
                .filter(file -> file.getKind() == FileKind.FILE)
                .sorted(Comparator.comparing(file -> buildPath(file, byId), String.CASE_INSENSITIVE_ORDER))
                .map(file -> sourceOptionMap(file, byId, 0))
                .toList();

        List<Map<String, Object>> folders = all.stream()
                .filter(file -> file.getKind() == FileKind.FOLDER)
                .sorted(Comparator.comparing(file -> buildPath(file, byId), String.CASE_INSENSITIVE_ORDER))
                .map(file -> sourceOptionMap(file, byId, countKnowledgeFiles(user.getId(), file.getId())))
                .toList();

        return Map.of(
                "files", files,
                "folders", folders,
                "fileCount", files.size(),
                "folderCount", folders.size(),
                "knowledgeFileCount", files.stream().filter(item -> Boolean.TRUE.equals(item.get("knowledgeReady"))).count()
        );
    }

    private String systemPrompt() {
        return "你是 CloudMind 的 AI 知识库问答助手。只能依据用户选择范围内的知识库片段回答，不要编造片段中没有的信息。"
                + "回答要使用中文，先给直接答案，再列出依据；如果资料不足，请明确说资料不足。"
                + "引用依据时使用文件名，例如“根据《文件名》”。";
    }

    private String overviewPrompt() {
        return "你是 CloudMind 的 AI 知识库概述助手。你需要把用户选择的文件、文件夹或全部文件综合成清晰概述。"
                + "只能依据给定资料，使用中文，表达适合学生或项目管理者阅读。";
    }

    private boolean isAiReady(AiConfig config) {
        return config != null
                && Boolean.TRUE.equals(config.getEnabled())
                && config.getApiKey() != null
                && !config.getApiKey().isBlank();
    }

    private boolean hasKnowledgeText(CloudFile file) {
        return file != null && (notBlank(file.getExtractedText()) || notBlank(file.getSummary()) || notBlank(file.getTags()) || notBlank(file.getName()));
    }

    private ScoredFile score(CloudFile file, String question, Set<String> terms) {
        String name = lower(file.getName());
        String summary = lower(file.getSummary());
        String tags = lower(file.getTags());
        String text = lower(file.getExtractedText());
        int score = 0;
        String q = lower(question);
        if (notBlank(q)) {
            if (name.contains(q)) score += 80;
            if (summary.contains(q)) score += 60;
            if (tags.contains(q)) score += 50;
            if (text.contains(q)) score += 40;
        }
        for (String term : terms) {
            if (term.length() < 2) continue;
            if (name.contains(term)) score += 30;
            if (tags.contains(term)) score += 20;
            if (summary.contains(term)) score += 16;
            score += Math.min(12, countOccurrences(text, term));
        }
        if (notBlank(file.getExtractedText())) score += 2;
        return new ScoredFile(file, score, snippet(file, terms));
    }

    private Comparator<ScoredFile> scoredComparator(boolean overviewIntent) {
        if (overviewIntent) {
            return Comparator.comparing((ScoredFile scored) -> scored.file().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return Comparator.comparingInt(ScoredFile::score).reversed()
                .thenComparing(scored -> scored.file().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private String buildContext(List<ScoredFile> ranked, Set<String> terms) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ScoredFile scored : ranked) {
            CloudFile file = scored.file();
            String text = firstNonBlank(scored.snippet(), file.getSummary(), file.getExtractedText());
            text = trimTo(text, 2000);
            String block = "[资料" + index + "] 文件名：" + file.getName() + "\n"
                    + "摘要：" + Optional.ofNullable(file.getSummary()).orElse("无") + "\n"
                    + "标签：" + Optional.ofNullable(file.getTags()).orElse("无") + "\n"
                    + "相关片段：" + text + "\n\n";
            if (sb.length() + block.length() > MAX_CONTEXT_CHARS) break;
            sb.append(block);
            index++;
        }
        return sb.toString();
    }

    private String localAnswer(String question, List<ScoredFile> ranked, ScopeSelection scope, Set<String> terms) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索范围：").append(scope.label()).append("。根据当前知识库检索，我找到了 ").append(ranked.size()).append(" 个相关文件。\n\n");
        int i = 1;
        for (ScoredFile scored : ranked) {
            CloudFile file = scored.file();
            String evidence = firstNonBlank(scored.snippet(), file.getSummary(), file.getExtractedText(), "暂无可摘录文本。");
            sb.append(i).append(". 《").append(file.getName()).append("》：")
                    .append(trimTo(evidence.replaceAll("\\s+", " "), 260)).append("\n");
            i++;
        }
        sb.append("\n建议：如果你希望我把这些片段综合成完整答案，请先在管理后台配置 AI API。你的问题是：").append(question);
        return sb.toString();
    }

    private String localOverview(ScopeSelection scope, List<ScoredFile> selected) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(scope.label()).append("概述】\n");
        sb.append("当前共选取 ").append(selected.size()).append(" 个可用资料文件。\n\n");
        int i = 1;
        for (ScoredFile scored : selected) {
            CloudFile file = scored.file();
            String evidence = firstNonBlank(file.getSummary(), scored.snippet(), file.getExtractedText(), "暂无摘要内容。");
            sb.append(i).append(". 《").append(file.getName()).append("》：")
                    .append(trimTo(evidence.replaceAll("\\s+", " "), 300)).append("\n");
            i++;
        }
        sb.append("\n可继续追问：这些资料的重点是什么、如何整理成提纲、哪些内容适合写进项目介绍。 ");
        return sb.toString();
    }

    private Map<String, Object> sourceMap(ScoredFile scored) {
        CloudFile file = scored.file();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.getId());
        map.put("name", file.getName());
        map.put("summary", file.getSummary());
        map.put("tags", splitTags(file.getTags()));
        map.put("score", scored.score());
        map.put("snippet", trimTo(scored.snippet(), 220));
        map.put("updatedAt", file.getUpdatedAt());
        return map;
    }

    private Map<String, Object> scopeMap(ScopeSelection scope) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", scope.type());
        map.put("id", scope.id());
        map.put("ids", scope.files().stream().map(CloudFile::getId).toList());
        map.put("label", scope.label());
        map.put("fileCount", scope.files().size());
        return map;
    }

    private Map<String, Object> sourceOptionMap(CloudFile file, Map<Long, CloudFile> byId, int childKnowledgeCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.getId());
        map.put("name", file.getName());
        map.put("kind", file.getKind().name());
        map.put("parentId", file.getParentId());
        map.put("path", buildPath(file, byId));
        map.put("summary", file.getSummary());
        map.put("tags", splitTags(file.getTags()));
        map.put("sizeBytes", file.getSizeBytes());
        map.put("updatedAt", file.getUpdatedAt());
        map.put("knowledgeReady", file.getKind() == FileKind.FILE && hasKnowledgeText(file));
        map.put("childKnowledgeCount", childKnowledgeCount);
        return map;
    }

    private List<String> buildSuggestions(List<ScoredFile> ranked) {
        return ranked.stream()
                .map(scored -> scored.file().getName())
                .filter(Objects::nonNull)
                .limit(3)
                .map(name -> "继续追问《" + name + "》里的重点")
                .toList();
    }

    private String callChat(AiConfig config, String systemPrompt, String userContent, int maxTokens) throws Exception {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("temperature", 0.2);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(45))
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

    private ScopeSelection resolveScope(AppUser user, String rawScopeType, Long scopeId, List<Long> scopeIds) {
        String type = rawScopeType == null || rawScopeType.isBlank() ? "ALL" : rawScopeType.trim().toUpperCase(Locale.ROOT);
        if ("FILE".equals(type)) {
            List<Long> ids = normalizeScopeIds(scopeIds);
            if (ids.isEmpty() && scopeId != null) ids = List.of(scopeId);
            if (ids.isEmpty()) throw new IllegalArgumentException("请选择要生成概述或问答的文件");

            List<CloudFile> files = new ArrayList<>();
            for (Long id : ids) {
                CloudFile file = fileRepository.findByOwnerIdAndIdAndDeletedFalse(user.getId(), id)
                        .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权访问：" + id));
                if (file.getKind() != FileKind.FILE) throw new IllegalArgumentException("请选择文件而不是文件夹：" + file.getName());
                files.add(file);
            }
            String label = files.size() == 1
                    ? "文件《" + files.get(0).getName() + "》"
                    : "已选择 " + files.size() + " 个文件";
            Long displayId = files.size() == 1 ? files.get(0).getId() : null;
            return new ScopeSelection("FILE", displayId, label, files);
        }
        if ("FOLDER".equals(type)) {
            if (scopeId == null) throw new IllegalArgumentException("请选择要生成概述或问答的文件夹");
            CloudFile folder = fileRepository.findByOwnerIdAndIdAndDeletedFalse(user.getId(), scopeId)
                    .orElseThrow(() -> new IllegalArgumentException("文件夹不存在或无权访问"));
            if (folder.getKind() != FileKind.FOLDER) throw new IllegalArgumentException("请选择文件夹而不是文件");
            List<CloudFile> files = new ArrayList<>();
            collectFilesRecursive(user.getId(), folder.getId(), files);
            return new ScopeSelection("FOLDER", folder.getId(), "文件夹《" + folder.getName() + "》", files);
        }
        List<CloudFile> files = fileRepository.findByOwnerIdAndKindAndDeletedFalse(user.getId(), FileKind.FILE);
        return new ScopeSelection("ALL", null, "全部文件", files);
    }

    private List<Long> normalizeScopeIds(List<Long> scopeIds) {
        if (scopeIds == null || scopeIds.isEmpty()) return List.of();
        return scopeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(50)
                .toList();
    }

    private void collectFilesRecursive(Long ownerId, Long parentId, List<CloudFile> result) {
        List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(ownerId, parentId);
        for (CloudFile child : children) {
            if (child.getKind() == FileKind.FILE) {
                result.add(child);
            } else if (child.getKind() == FileKind.FOLDER) {
                collectFilesRecursive(ownerId, child.getId(), result);
            }
        }
    }

    private int countKnowledgeFiles(Long ownerId, Long parentId) {
        int count = 0;
        List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(ownerId, parentId);
        for (CloudFile child : children) {
            if (child.getKind() == FileKind.FILE && hasKnowledgeText(child)) {
                count++;
            } else if (child.getKind() == FileKind.FOLDER) {
                count += countKnowledgeFiles(ownerId, child.getId());
            }
        }
        return count;
    }

    private String buildPath(CloudFile file, Map<Long, CloudFile> byId) {
        Deque<String> names = new ArrayDeque<>();
        CloudFile current = file;
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getId() != null && !visited.contains(current.getId())) {
            visited.add(current.getId());
            names.addFirst(current.getName());
            Long parentId = current.getParentId();
            current = parentId == null ? null : byId.get(parentId);
        }
        return String.join(" / ", names);
    }

    private String snippet(CloudFile file, Set<String> terms) {
        String text = firstNonBlank(file.getExtractedText(), file.getSummary(), file.getName());
        if (!notBlank(text)) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String term : terms) {
            if (term.length() < 2) continue;
            int idx = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        if (best < 0) return trimTo(text, 600);
        int start = Math.max(0, best - 220);
        int end = Math.min(text.length(), best + 520);
        return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
    }

    private String overviewSnippet(CloudFile file) {
        return trimTo(firstNonBlank(file.getSummary(), file.getExtractedText(), file.getName()), 700);
    }

    private boolean isOverviewQuestion(String question) {
        String q = lower(question);
        return q.contains("总结") || q.contains("概述") || q.contains("摘要") || q.contains("整理") || q.contains("提纲")
                || q.contains("overview") || q.contains("summarize") || q.contains("summary");
    }

    private int normalizeTopK(Integer rawTopK) {
        return Math.max(3, Math.min(rawTopK == null ? 5 : rawTopK, 30));
    }

    private int normalizeOverviewTopK(Integer rawTopK) {
        return Math.max(3, Math.min(rawTopK == null ? 12 : rawTopK, 50));
    }

    private Set<String> keywords(String text) {
        if (text == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String part : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            String value = part.trim();
            if (value.length() >= 2 && value.length() <= 30) result.add(value);
            if (value.matches(".*[\\u4e00-\\u9fa5].*") && value.length() > 2) {
                for (int i = 0; i < value.length() - 1 && result.size() < 80; i++) {
                    result.add(value.substring(i, i + 2));
                }
            }
            if (result.size() >= 80) break;
        }
        return result;
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(8)
                .collect(Collectors.toList());
    }

    private String cleanQuestion(String rawQuestion) {
        if (rawQuestion == null || rawQuestion.trim().isBlank()) throw new IllegalArgumentException("问题不能为空");
        String question = rawQuestion.trim();
        if (question.length() > 500) throw new IllegalArgumentException("问题不能超过 500 个字符");
        return question;
    }

    private int countOccurrences(String text, String term) {
        if (!notBlank(text) || !notBlank(term)) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0 && count < 12) {
            count++;
            idx += Math.max(1, term.length());
        }
        return count;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isBlank();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (notBlank(value)) return value.trim();
        }
        return "";
    }

    private String trimTo(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record ScoredFile(CloudFile file, int score, String snippet) {}
    private record ScopeSelection(String type, Long id, String label, List<CloudFile> files) {}
}
