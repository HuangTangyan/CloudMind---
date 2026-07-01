package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.entity.CloudFile;
import com.cloudmind.demo.entity.FileKind;
import com.cloudmind.demo.entity.FileVersion;
import com.cloudmind.demo.repository.CloudFileRepository;
import com.cloudmind.demo.repository.FileVersionRepository;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileService {
    private final CloudFileRepository fileRepository;
    private final FileVersionRepository versionRepository;
    private final MinioStorageService storageService;
    private final TextAnalyzeService textAnalyzeService;

    public FileService(CloudFileRepository fileRepository,
                       FileVersionRepository versionRepository,
                       MinioStorageService storageService,
                       TextAnalyzeService textAnalyzeService) {
        this.fileRepository = fileRepository;
        this.versionRepository = versionRepository;
        this.storageService = storageService;
        this.textAnalyzeService = textAnalyzeService;
    }

    public Map<String, Object> list(AppUser user, Long parentId) {
        if (parentId != null) requireFolder(user, parentId, false);
        List<CloudFile> files = parentId == null
                ? fileRepository.findByOwnerIdAndParentIdIsNullAndDeletedFalseOrderByKindAscNameAsc(user.getId())
                : fileRepository.findByOwnerIdAndParentIdAndDeletedFalseOrderByKindAscNameAsc(user.getId(), parentId);
        long used = fileRepository.sumUsedBytes(user.getId());
        return Map.of(
                "items", files.stream().map(this::toMap).toList(),
                "usedBytes", used,
                "quotaBytes", user.getQuotaBytes(),
                "remainingBytes", Math.max(0L, user.getQuotaBytes() - used),
                "elasticExtraBytes", user.getQuotaBytes() / 2,
                "effectiveUploadLimitBytes", user.getQuotaBytes() + user.getQuotaBytes() / 2
        );
    }

    public List<Map<String, Object>> allFolders(AppUser user) {
        return fileRepository.findByOwnerIdAndKindAndDeletedFalse(user.getId(), FileKind.FOLDER)
                .stream()
                .sorted(Comparator.comparing(CloudFile::getName, String.CASE_INSENSITIVE_ORDER))
                .map(f -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", f.getId());
                    map.put("name", f.getName());
                    map.put("parentId", f.getParentId());
                    return map;
                })
                .toList();
    }

    public List<Map<String, Object>> trash(AppUser user) {
        return fileRepository.findByOwnerIdAndDeletedTrueOrderByDeletedAtDesc(user.getId())
                .stream()
                .filter(f -> f.getParentId() == null || fileRepository.findByOwnerIdAndId(user.getId(), f.getParentId()).map(p -> !Boolean.TRUE.equals(p.getDeleted())).orElse(true))
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> createFolder(AppUser user, Long parentId, String rawName) {
        String name = cleanName(rawName);
        if (parentId != null) requireFolder(user, parentId, false);
        ensureNoDuplicate(user, parentId, name, null);

        CloudFile folder = new CloudFile();
        folder.setOwner(user);
        folder.setParentId(parentId);
        folder.setName(name);
        folder.setKind(FileKind.FOLDER);
        folder.setSizeBytes(0L);
        folder.setSummary("文件夹");
        folder.setTags("文件夹");
        fileRepository.save(folder);
        return toMap(folder);
    }

    @Transactional
    public Map<String, Object> upload(AppUser user, Long parentId, MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (parentId != null) requireFolder(user, parentId, false);
        String originalName = cleanName(Optional.ofNullable(multipartFile.getOriginalFilename()).orElse("未命名文件"));
        return saveUploadedFile(user, parentId, multipartFile, originalName);
    }

    @Transactional
    public List<Map<String, Object>> uploadFolder(AppUser user, Long parentId, List<MultipartFile> files, List<String> relativePaths) {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("上传文件夹不能为空");
        if (parentId != null) requireFolder(user, parentId, false);
        long totalSize = files.stream().filter(Objects::nonNull).mapToLong(MultipartFile::getSize).sum();
        ensureUploadAllowed(user, totalSize, 0L, "容量不足，无法上传文件夹。当前系统允许最多临时超出配额 50%，但该文件夹仍超过可上传上限。");
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Long> folderCache = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) continue;
            String relativePath = relativePaths != null && i < relativePaths.size()
                    ? relativePaths.get(i)
                    : Optional.ofNullable(file.getOriginalFilename()).orElse("未命名文件");
            String[] parts = relativePath.replace("\\", "/").split("/");
            Long currentParent = parentId;
            StringBuilder cacheKey = new StringBuilder(parentId == null ? "root" : String.valueOf(parentId));
            for (int p = 0; p < Math.max(0, parts.length - 1); p++) {
                String folderName = cleanName(parts[p]);
                cacheKey.append('/').append(folderName.toLowerCase(Locale.ROOT));
                String key = cacheKey.toString();
                if (folderCache.containsKey(key)) {
                    currentParent = folderCache.get(key);
                    continue;
                }
                Optional<CloudFile> existing = findActiveSibling(user, currentParent, folderName)
                        .filter(f -> f.getKind() == FileKind.FOLDER);
                CloudFile folder;
                if (existing.isPresent()) {
                    folder = existing.get();
                } else {
                    folder = new CloudFile();
                    folder.setOwner(user);
                    folder.setParentId(currentParent);
                    folder.setName(folderName);
                    folder.setKind(FileKind.FOLDER);
                    folder.setSizeBytes(0L);
                    folder.setSummary("文件夹");
                    folder.setTags("文件夹");
                    fileRepository.save(folder);
                }
                currentParent = folder.getId();
                folderCache.put(key, currentParent);
            }
            String finalName = parts.length == 0 ? Optional.ofNullable(file.getOriginalFilename()).orElse("未命名文件") : parts[parts.length - 1];
            result.add(saveUploadedFile(user, currentParent, file, finalName));
        }
        return result;
    }

    private Map<String, Object> saveUploadedFile(AppUser user, Long parentId, MultipartFile multipartFile, String rawName) {
        String originalName = cleanName(rawName);
        Optional<CloudFile> oldSameName = findActiveSibling(user, parentId, originalName)
                .filter(f -> f.getKind() == FileKind.FILE);
        long replacedSize = oldSameName.map(CloudFile::getSizeBytes).orElse(0L);
        ensureUploadAllowed(user, multipartFile.getSize(), replacedSize, "容量不足，无法上传。当前系统允许最多临时超出配额 50%，但该文件仍超过可上传上限。");
        String objectName = newObjectName(user, originalName);
        storageService.upload(objectName, multipartFile);
        TextAnalyzeService.AnalysisResult analysis = analyzeObject(objectName, originalName,
                multipartFile.getContentType(), multipartFile.getSize());

        if (oldSameName.isPresent()) {
            CloudFile old = oldSameName.get();
            createVersion(old);
            old.setObjectName(objectName);
            old.setContentType(multipartFile.getContentType() == null ? "application/octet-stream" : multipartFile.getContentType());
            old.setSizeBytes(multipartFile.getSize());
            old.setSummary(analysis.summary());
            old.setTags(analysis.tags());
            old.setExtractedText(analysis.extractedText());
            old.setReviewStatus("PENDING");
            old.setReviewNote("文件重新上传后等待管理员或 AI 审查");
            return toMap(fileRepository.save(old));
        }

        findActiveSibling(user, parentId, originalName).ifPresent(f -> {
            throw new IllegalArgumentException("同一目录下已存在同名文件夹");
        });

        CloudFile file = new CloudFile();
        file.setOwner(user);
        file.setParentId(parentId);
        file.setName(originalName);
        file.setKind(FileKind.FILE);
        file.setObjectName(objectName);
        file.setContentType(multipartFile.getContentType() == null ? "application/octet-stream" : multipartFile.getContentType());
        file.setSizeBytes(multipartFile.getSize());
        file.setSummary(analysis.summary());
        file.setTags(analysis.tags());
        file.setExtractedText(analysis.extractedText());
        file.setReviewStatus("PENDING");
        file.setReviewNote("新上传文件等待管理员或 AI 审查");
        fileRepository.save(file);
        return toMap(file);
    }

    @Transactional
    public Map<String, Object> reanalyze(AppUser user, Long fileId) {
        CloudFile file = requireFile(user, fileId, false);
        TextAnalyzeService.AnalysisResult analysis = analyzeObject(file.getObjectName(), file.getName(), file.getContentType(), file.getSizeBytes());
        file.setSummary(analysis.summary());
        file.setTags(analysis.tags());
        file.setExtractedText(analysis.extractedText());
        file.setReviewStatus("PENDING");
        file.setReviewNote("文件重新生成摘要/标签后等待管理员或 AI 审查");
        return toMap(fileRepository.save(file));
    }

    @Transactional
    public Map<String, Object> adminReanalyze(Long fileId) {
        CloudFile file = requireAdminFile(fileId);
        TextAnalyzeService.AnalysisResult analysis = analyzeObject(file.getObjectName(), file.getName(), file.getContentType(), file.getSizeBytes());
        file.setSummary(analysis.summary());
        file.setTags(analysis.tags());
        file.setExtractedText(analysis.extractedText());
        file.setReviewStatus("PENDING");
        file.setReviewNote("管理员重新生成摘要/标签后等待 AI 或人工审查");
        return toMap(fileRepository.save(file));
    }

    public ResponseEntity<InputStreamResource> download(AppUser user, Long fileId, boolean inline) {
        CloudFile file = requireFile(user, fileId, false);
        return downloadRaw(file, inline);
    }

    private ResponseEntity<InputStreamResource> downloadRaw(CloudFile file, boolean inline) {
        InputStreamResource resource = storageService.download(file.getObjectName());
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            mediaType = MediaType.parseMediaType(file.getContentType());
        } catch (Exception ignored) {}

        ContentDisposition disposition = ContentDisposition.builder(inline ? "inline" : "attachment")
                .filename(file.getName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    public Map<String, Object> preview(AppUser user, Long fileId, String token) {
        CloudFile file = requireFile(user, fileId, false);
        Map<String, Object> map = toMap(file);
        map.put("previewType", previewType(file));
        map.put("text", Optional.ofNullable(file.getExtractedText()).orElse(""));
        map.put("inlineUrl", "/api/files/" + file.getId() + "/download?disposition=inline&token=" + token);
        return map;
    }

    public Map<String, Object> detail(AppUser user, Long fileId) {
        CloudFile file = requireItem(user, fileId, false);
        return toMap(file);
    }

    @Transactional
    public Map<String, Object> rename(AppUser user, Long fileId, String rawName) {
        CloudFile file = requireItem(user, fileId, false);
        String name = cleanName(rawName);
        ensureNoDuplicate(user, file.getParentId(), name, file.getId());
        file.setName(name);
        return toMap(fileRepository.save(file));
    }

    @Transactional
    public Map<String, Object> move(AppUser user, Long fileId, Long targetParentId) {
        CloudFile file = requireItem(user, fileId, false);
        if (targetParentId != null) {
            CloudFile target = requireFolder(user, targetParentId, false);
            if (Objects.equals(file.getId(), target.getId())) {
                throw new IllegalArgumentException("不能移动到自己下面");
            }
            if (file.getKind() == FileKind.FOLDER && isDescendant(user, target.getId(), file.getId())) {
                throw new IllegalArgumentException("不能移动到自己的子文件夹下面");
            }
        }
        ensureNoDuplicate(user, targetParentId, file.getName(), file.getId());
        file.setParentId(targetParentId);
        return toMap(fileRepository.save(file));
    }

    @Transactional
    public Map<String, Object> copy(AppUser user, Long fileId, Long targetParentId) {
        CloudFile file = requireItem(user, fileId, false);
        if (targetParentId != null) requireFolder(user, targetParentId, false);
        ensureUploadAllowed(user, totalSizeRecursive(user, file), 0L, "容量不足，无法复制。当前系统允许最多临时超出配额 50%，但复制内容仍超过可用上限。");
        CloudFile copy = copyRecursive(user, file, targetParentId);
        return toMap(copy);
    }

    @Transactional
    public void delete(AppUser user, Long fileId) {
        CloudFile item = requireItem(user, fileId, false);
        softDeleteRecursive(user, item);
    }

    @Transactional
    public void restore(AppUser user, Long fileId) {
        CloudFile item = requireItem(user, fileId, true);
        restoreRecursive(user, item);
    }

    @Transactional
    public void purge(AppUser user, Long fileId) {
        CloudFile item = requireItem(user, fileId, true);
        purgeRecursive(user, item);
    }

    public List<Map<String, Object>> search(AppUser user, String keyword) {
        if (keyword == null || keyword.trim().isBlank()) {
            return Collections.emptyList();
        }
        return fileRepository.search(user.getId(), keyword.trim())
                .stream().map(this::toMap).toList();
    }

    public List<Map<String, Object>> gallery(AppUser user) {
        return fileRepository.findByOwnerIdAndDeletedFalseOrderByKindAscNameAsc(user.getId())
                .stream()
                .filter(f -> f.getKind() == FileKind.FILE)
                .filter(f -> "IMAGE".equals(previewType(f)))
                .sorted(Comparator.comparing(CloudFile::getCreatedAt).reversed())
                .map(this::toMap)
                .toList();
    }

    public List<Map<String, Object>> related(AppUser user, Long fileId) {
        CloudFile file = requireFile(user, fileId, false);
        Set<String> baseTags = splitTags(file.getTags());
        Set<String> baseWords = keywords(file.getName() + " " + file.getSummary() + " " + file.getExtractedText());
        return fileRepository.findByOwnerIdAndKindAndDeletedFalse(user.getId(), FileKind.FILE)
                .stream()
                .filter(f -> !Objects.equals(f.getId(), file.getId()))
                .map(f -> Map.entry(f, relationScore(f, baseTags, baseWords)))
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(e -> toMap(e.getKey()))
                .toList();
    }

    public List<Map<String, Object>> versions(AppUser user, Long fileId) {
        requireFile(user, fileId, false);
        return versionRepository.findVersions(user.getId(), fileId)
                .stream()
                .map(this::versionMap)
                .toList();
    }

    @Transactional
    public Map<String, Object> restoreVersion(AppUser user, Long fileId, Long versionId) {
        CloudFile file = requireFile(user, fileId, false);
        FileVersion version = versionRepository.findVersion(user.getId(), fileId, versionId)
                .orElseThrow(() -> new IllegalArgumentException("历史版本不存在"));

        createVersion(file);
        file.setObjectName(version.getObjectName());
        file.setContentType(version.getContentType());
        file.setSizeBytes(version.getSizeBytes());
        file.setSummary(version.getSummary());
        file.setTags(version.getTags());
        file.setExtractedText(version.getExtractedText());
        fileRepository.save(file);
        // 被恢复的版本对象已经变成当前文件对象，避免同一个 objectName 同时属于当前文件和历史版本。
        versionRepository.delete(version);
        // currentObject 已经在 createVersion(file) 中保存为一个新的历史版本，不能删除。
        return toMap(file);
    }

    public long usedBytes(Long ownerId) {
        return fileRepository.sumUsedBytes(ownerId);
    }

    public Map<String, Object> adminListFiles(Long ownerId, Long parentId) {
        List<CloudFile> files = parentId == null
                ? fileRepository.findByOwnerIdAndParentIdIsNullAndDeletedFalseOrderByKindAscNameAsc(ownerId)
                : fileRepository.findByOwnerIdAndParentIdAndDeletedFalseOrderByKindAscNameAsc(ownerId, parentId);
        return Map.of(
                "items", files.stream().map(this::toAdminAuditMap).toList(),
                "usedBytes", fileRepository.sumUsedBytes(ownerId)
        );
    }

    public ResponseEntity<InputStreamResource> adminDownload(Long fileId, boolean inline) {
        CloudFile file = requireAdminFile(fileId);
        return downloadRaw(file, inline);
    }

    public Map<String, Object> adminPreview(Long fileId, String token) {
        CloudFile file = requireAdminFile(fileId);
        Map<String, Object> map = toMap(file);
        map.put("previewType", previewType(file));
        map.put("text", Optional.ofNullable(file.getExtractedText()).orElse(""));
        map.put("inlineUrl", "/api/admin/files/" + file.getId() + "/download?disposition=inline&token=" + token);
        map.put("downloadUrl", "/api/admin/files/" + file.getId() + "/download?disposition=attachment&token=" + token);
        return map;
    }

    @Transactional
    public Map<String, Object> adminUpdateReview(Long fileId, String status, String note) {
        CloudFile file = fileRepository.findById(fileId).orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        String value = status == null || status.isBlank() ? "NORMAL" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NORMAL", "ABNORMAL", "PENDING").contains(value)) {
            throw new IllegalArgumentException("审查状态只能是 NORMAL、ABNORMAL 或 PENDING");
        }
        file.setReviewStatus(value);
        file.setReviewNote(note == null ? null : note.trim());
        return toMap(fileRepository.save(file));
    }

    @Transactional
    public void adminPurgeAllFilesOfUser(Long ownerId) {
        List<CloudFile> files = new ArrayList<>(fileRepository.findByOwnerId(ownerId));
        for (CloudFile item : files) {
            if (item.getKind() == FileKind.FILE) {
                storageService.delete(item.getObjectName());
                for (FileVersion version : versionRepository.findByFileId(item.getId())) {
                    storageService.delete(version.getObjectName());
                }
                versionRepository.deleteByFileId(item.getId());
            }
        }
        fileRepository.deleteAll(files);
    }

    private TextAnalyzeService.AnalysisResult analyzeObject(String objectName, String name, String contentType, long sizeBytes) {
        return textAnalyzeService.analyze(storageService.stream(objectName), name, contentType, sizeBytes);
    }

    private void ensureUploadAllowed(AppUser user, long incomingBytes, long replacingBytes, String message) {
        long used = fileRepository.sumUsedBytes(user.getId());
        long quota = Math.max(0L, user.getQuotaBytes());
        long effectiveLimit = quota + quota / 2;
        if (used - replacingBytes + incomingBytes > effectiveLimit) {
            throw new IllegalArgumentException(message);
        }
    }

    private long totalSizeRecursive(AppUser user, CloudFile source) {
        if (source.getKind() == FileKind.FILE) return source.getSizeBytes() == null ? 0L : source.getSizeBytes();
        long total = 0L;
        for (CloudFile child : fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), source.getId())) {
            total += totalSizeRecursive(user, child);
        }
        return total;
    }

    private CloudFile requireAdminFile(Long fileId) {
        CloudFile file = fileRepository.findById(fileId).orElseThrow(() -> new IllegalArgumentException("文件不存在"));
        if (Boolean.TRUE.equals(file.getDeleted())) throw new IllegalArgumentException("文件已在回收站，不能预览");
        if (file.getKind() != FileKind.FILE) throw new IllegalArgumentException("该项目不是文件");
        return file;
    }

    private void createVersion(CloudFile file) {
        if (file.getKind() != FileKind.FILE || file.getObjectName() == null) return;
        FileVersion version = new FileVersion();
        version.setFile(file);
        version.setObjectName(file.getObjectName());
        version.setName(file.getName());
        version.setContentType(file.getContentType());
        version.setSizeBytes(file.getSizeBytes());
        version.setSummary(file.getSummary());
        version.setTags(file.getTags());
        version.setExtractedText(file.getExtractedText());
        versionRepository.save(version);
    }

    private CloudFile copyRecursive(AppUser user, CloudFile source, Long targetParentId) {
        String name = uniqueName(user, targetParentId, source.getName());
        CloudFile copy = new CloudFile();
        copy.setOwner(user);
        copy.setParentId(targetParentId);
        copy.setName(name);
        copy.setKind(source.getKind());
        copy.setContentType(source.getContentType());
        copy.setSizeBytes(source.getSizeBytes());
        copy.setSummary(source.getSummary());
        copy.setTags(source.getTags());
        copy.setExtractedText(source.getExtractedText());
        if (source.getKind() == FileKind.FILE) {
            String targetObject = newObjectName(user, name);
            storageService.copy(source.getObjectName(), targetObject);
            copy.setObjectName(targetObject);
        }
        fileRepository.save(copy);
        if (source.getKind() == FileKind.FOLDER) {
            List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), source.getId());
            for (CloudFile child : children) copyRecursive(user, child, copy.getId());
        }
        return copy;
    }

    private void softDeleteRecursive(AppUser user, CloudFile item) {
        if (item.getKind() == FileKind.FOLDER) {
            List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), item.getId());
            for (CloudFile child : children) softDeleteRecursive(user, child);
        }
        item.setDeleted(true);
        item.setDeletedAt(Instant.now());
        fileRepository.save(item);
    }

    private void restoreRecursive(AppUser user, CloudFile item) {
        if (item.getParentId() != null) {
            CloudFile parent = fileRepository.findByOwnerIdAndId(user.getId(), item.getParentId()).orElse(null);
            if (parent == null || Boolean.TRUE.equals(parent.getDeleted())) {
                item.setParentId(null);
            }
        }
        item.setName(uniqueName(user, item.getParentId(), item.getName(), item.getId()));
        item.setDeleted(false);
        item.setDeletedAt(null);
        fileRepository.save(item);
        if (item.getKind() == FileKind.FOLDER) {
            List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedTrue(user.getId(), item.getId());
            for (CloudFile child : children) restoreRecursive(user, child);
        }
    }

    private void purgeRecursive(AppUser user, CloudFile item) {
        if (item.getKind() == FileKind.FOLDER) {
            List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedTrue(user.getId(), item.getId());
            for (CloudFile child : children) purgeRecursive(user, child);
        } else {
            storageService.delete(item.getObjectName());
            for (FileVersion version : versionRepository.findByFileId(item.getId())) {
                storageService.delete(version.getObjectName());
            }
            versionRepository.deleteByFileId(item.getId());
        }
        fileRepository.delete(item);
    }

    private CloudFile requireItem(AppUser user, Long id, boolean includeDeleted) {
        if (id == null) throw new IllegalArgumentException("文件 ID 不能为空");
        Optional<CloudFile> result = includeDeleted
                ? fileRepository.findByOwnerIdAndId(user.getId(), id)
                : fileRepository.findByOwnerIdAndIdAndDeletedFalse(user.getId(), id);
        return result.orElseThrow(() -> new IllegalArgumentException("文件不存在或无权限"));
    }

    private CloudFile requireFile(AppUser user, Long id, boolean includeDeleted) {
        CloudFile file = requireItem(user, id, includeDeleted);
        if (file.getKind() != FileKind.FILE) throw new IllegalArgumentException("该项目不是文件");
        return file;
    }

    private CloudFile requireFolder(AppUser user, Long id, boolean includeDeleted) {
        CloudFile folder = requireItem(user, id, includeDeleted);
        if (folder.getKind() != FileKind.FOLDER) throw new IllegalArgumentException("目标不是文件夹");
        return folder;
    }

    private void ensureNoDuplicate(AppUser user, Long parentId, String name, Long exceptId) {
        List<CloudFile> siblings = parentId == null
                ? fileRepository.findByOwnerIdAndParentIdIsNullAndDeletedFalse(user.getId())
                : fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), parentId);
        boolean duplicated = siblings.stream()
                .anyMatch(f -> f.getName().equalsIgnoreCase(name) && !Objects.equals(f.getId(), exceptId));
        if (duplicated) throw new IllegalArgumentException("同一目录下已存在同名项目");
    }

    private Optional<CloudFile> findActiveSibling(AppUser user, Long parentId, String name) {
        List<CloudFile> siblings = parentId == null
                ? fileRepository.findByOwnerIdAndParentIdIsNullAndDeletedFalse(user.getId())
                : fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), parentId);
        return siblings.stream().filter(f -> f.getName().equalsIgnoreCase(name)).findFirst();
    }

    private String cleanName(String rawName) {
        if (rawName == null) throw new IllegalArgumentException("名称不能为空");
        String name = rawName.trim().replace("\\", "_").replace("/", "_");
        if (name.isBlank()) throw new IllegalArgumentException("名称不能为空");
        if (name.length() > 180) throw new IllegalArgumentException("名称不能超过 180 个字符");
        return name;
    }

    private String newObjectName(AppUser user, String name) {
        return "users/%d/%s/%s".formatted(user.getId(), UUID.randomUUID(), cleanName(name));
    }

    private boolean isDescendant(AppUser user, Long childFolderId, Long possibleAncestorId) {
        Long parent = childFolderId;
        while (parent != null) {
            if (Objects.equals(parent, possibleAncestorId)) return true;
            CloudFile current = fileRepository.findByOwnerIdAndId(user.getId(), parent).orElse(null);
            parent = current == null ? null : current.getParentId();
        }
        return false;
    }

    private String uniqueName(AppUser user, Long parentId, String rawName) {
        return uniqueName(user, parentId, rawName, null);
    }

    private String uniqueName(AppUser user, Long parentId, String rawName, Long exceptId) {
        String name = cleanName(rawName);
        List<CloudFile> siblings = parentId == null
                ? fileRepository.findByOwnerIdAndParentIdIsNullAndDeletedFalse(user.getId())
                : fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(user.getId(), parentId);
        Set<String> used = siblings.stream()
                .filter(f -> !Objects.equals(f.getId(), exceptId))
                .map(f -> f.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!used.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 9999; i++) {
            String candidate = base + "_副本" + i + ext;
            if (!used.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        return base + "_副本" + UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    private String previewType(CloudFile file) {
        String ct = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/") || name.matches(".*\\.(png|jpg|jpeg|gif|webp)$")) return "IMAGE";
        if (ct.startsWith("video/") || name.matches(".*\\.(mp4|webm|ogg|mov)$")) return "VIDEO";
        if (ct.startsWith("audio/") || name.matches(".*\\.(mp3|wav|ogg)$")) return "AUDIO";
        if (ct.equals("application/pdf") || name.endsWith(".pdf")) return "PDF";
        if (ct.startsWith("text/") || name.matches(".*\\.(txt|md|json|xml|html|css|js|java|py|sql|yml|yaml)$")) return "TEXT";
        if (file.getExtractedText() != null && !file.getExtractedText().isBlank()) return "TEXT";
        return "OTHER";
    }

    private Set<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private Set<String> keywords(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .map(String::trim)
                .filter(s -> s.length() >= 2 && s.length() <= 20)
                .limit(80)
                .collect(Collectors.toSet());
    }

    private int relationScore(CloudFile f, Set<String> baseTags, Set<String> baseWords) {
        int score = 0;
        for (String tag : splitTags(f.getTags())) if (baseTags.contains(tag)) score += 5;
        for (String word : keywords(f.getName() + " " + f.getSummary() + " " + f.getExtractedText())) if (baseWords.contains(word)) score += 1;
        return score;
    }

    private Map<String, Object> toMap(CloudFile file) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", file.getId());
        map.put("parentId", file.getParentId());
        map.put("name", file.getName());
        map.put("kind", file.getKind().name());
        map.put("contentType", file.getContentType());
        map.put("sizeBytes", file.getSizeBytes());
        map.put("summary", file.getSummary());
        map.put("tags", splitTags(file.getTags()));
        map.put("deleted", file.getDeleted());
        map.put("deletedAt", file.getDeletedAt());
        map.put("reviewStatus", file.getReviewStatus() == null ? "NORMAL" : file.getReviewStatus());
        map.put("reviewNote", file.getReviewNote());
        map.put("createdAt", file.getCreatedAt());
        map.put("updatedAt", file.getUpdatedAt());
        map.put("versionCount", file.getKind() == FileKind.FILE ? versionRepository.countByFileId(file.getId()) : 0);
        return map;
    }

    private Map<String, Object> toAdminAuditMap(CloudFile file) {
        Map<String, Object> map = toMap(file);
        if (file.getKind() == FileKind.FOLDER) {
            ReviewAggregate aggregate = aggregateFolderReview(file.getOwner().getId(), file.getId());
            map.put("childAbnormalCount", aggregate.abnormal());
            map.put("childPendingCount", aggregate.pending());
            map.put("childFileCount", aggregate.totalFiles());
            if (aggregate.abnormal() > 0) {
                map.put("reviewStatus", "ABNORMAL");
                map.put("reviewNote", "文件夹内有 " + aggregate.abnormal() + " 个异常文件"
                        + (aggregate.pending() > 0 ? "，" + aggregate.pending() + " 个待审查文件" : ""));
                map.put("summary", "文件夹内发现异常内容，请打开文件夹逐级审查。" );
            } else if (aggregate.pending() > 0) {
                map.put("reviewStatus", "PENDING");
                map.put("reviewNote", "文件夹内有 " + aggregate.pending() + " 个待审查文件");
                map.put("summary", "文件夹内存在待审查文件。" );
            } else {
                map.put("reviewStatus", "NORMAL");
                map.put("reviewNote", aggregate.totalFiles() > 0 ? "文件夹内文件审查正常" : "空文件夹");
            }
        }
        return map;
    }

    private ReviewAggregate aggregateFolderReview(Long ownerId, Long folderId) {
        int abnormal = 0;
        int pending = 0;
        int totalFiles = 0;
        List<CloudFile> children = fileRepository.findByOwnerIdAndParentIdAndDeletedFalse(ownerId, folderId);
        for (CloudFile child : children) {
            if (child.getKind() == FileKind.FOLDER) {
                ReviewAggregate nested = aggregateFolderReview(ownerId, child.getId());
                abnormal += nested.abnormal();
                pending += nested.pending();
                totalFiles += nested.totalFiles();
            } else {
                totalFiles++;
                String status = child.getReviewStatus() == null ? "NORMAL" : child.getReviewStatus().toUpperCase(Locale.ROOT);
                if ("ABNORMAL".equals(status)) abnormal++;
                else if ("PENDING".equals(status)) pending++;
            }
        }
        return new ReviewAggregate(abnormal, pending, totalFiles);
    }

    private record ReviewAggregate(int abnormal, int pending, int totalFiles) {}

    private Map<String, Object> versionMap(FileVersion version) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", version.getId());
        map.put("name", version.getName());
        map.put("contentType", version.getContentType());
        map.put("sizeBytes", version.getSizeBytes());
        map.put("summary", version.getSummary());
        map.put("tags", splitTags(version.getTags()));
        map.put("createdAt", version.getCreatedAt());
        return map;
    }
}
