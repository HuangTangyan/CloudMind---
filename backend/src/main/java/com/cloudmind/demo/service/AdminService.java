package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.entity.FileKind;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.CloudFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {
    private final AppUserRepository userRepository;
    private final CloudFileRepository fileRepository;
    private final AuthService authService;
    private final FileService fileService;

    public AdminService(AppUserRepository userRepository,
                        CloudFileRepository fileRepository,
                        AuthService authService,
                        FileService fileService) {
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
        this.authService = authService;
        this.fileService = fileService;
    }

    public List<Map<String, Object>> users(AppUser admin) {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(u -> toAdminUserMap(admin, u))
                .toList();
    }

    @Transactional
    public Map<String, Object> createUser(AppUser admin, Map<String, Object> body) {
        String username = stringValue(body.get("username"));
        String password = stringValue(body.get("password"));
        String role = stringValue(body.getOrDefault("role", "USER"));
        Long quotaBytes = longValue(body.get("quotaBytes"));
        AppUser created = authService.createUserByAdmin(username, password, role, quotaBytes);
        return toAdminUserMap(admin, created);
    }

    @Transactional
    public Map<String, Object> updateUser(AppUser admin, Long userId, Map<String, Object> body) {
        String role = body.containsKey("role") ? stringValue(body.get("role")) : null;
        Long quotaBytes = body.containsKey("quotaBytes") ? longValue(body.get("quotaBytes")) : null;
        AppUser updated = authService.updateUserByAdmin(userId, role, quotaBytes);
        return toAdminUserMap(admin, updated);
    }

    @Transactional
    public Map<String, Object> setEnabled(AppUser admin, Long userId, boolean enabled) {
        AppUser updated = authService.setUserEnabledByAdmin(admin, userId, enabled);
        return toAdminUserMap(admin, updated);
    }

    @Transactional
    public String resetPassword(AppUser admin, Long userId) {
        return authService.resetPasswordByAdmin(admin, userId);
    }

    @Transactional
    public void deleteUser(AppUser admin, Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (admin.getId().equals(user.getId())) throw new IllegalArgumentException("不能删除当前登录的管理员账号");
        fileService.adminPurgeAllFilesOfUser(user.getId());
        userRepository.delete(user);
    }

    public List<Map<String, Object>> auditUsers(AppUser admin) {
        return users(admin);
    }

    public Map<String, Object> storageOverview() {
        List<AppUser> users = userRepository.findAll();
        long userCount = users.size();
        long quotaAllocatedBytes = users.stream()
                .map(AppUser::getQuotaBytes)
                .filter(v -> v != null && v > 0)
                .mapToLong(Long::longValue)
                .sum();
        long activeFileBytes = fileRepository.sumAllActiveFileBytes();
        long deletedFileBytes = fileRepository.sumAllDeletedFileBytes();
        long activeFileCount = fileRepository.countByKindAndDeletedFalse(FileKind.FILE);
        long activeFolderCount = fileRepository.countByKindAndDeletedFalse(FileKind.FOLDER);
        long deletedFileCount = fileRepository.countByKindAndDeletedTrue(FileKind.FILE);

        Map<String, Object> serverDisk = currentServerDisk();
        long serverTotalBytes = longFromMap(serverDisk, "totalBytes");
        long serverUsableBytes = longFromMap(serverDisk, "usableBytes");
        long serverUsedBytes = longFromMap(serverDisk, "usedBytes");
        double serverUsedPercent = percent(serverUsedBytes, serverTotalBytes);
        double serverUsablePercent = percent(serverUsableBytes, serverTotalBytes);
        String status = storageStatus(serverUsablePercent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedAt", Instant.now().toString());
        result.put("status", status);
        result.put("statusText", storageStatusText(status));
        result.put("serverTotalBytes", serverTotalBytes);
        result.put("serverUsedBytes", serverUsedBytes);
        result.put("serverUsableBytes", serverUsableBytes);
        result.put("serverFreeBytes", longFromMap(serverDisk, "freeBytes"));
        result.put("serverUsedPercent", serverUsedPercent);
        result.put("serverUsablePercent", serverUsablePercent);
        result.put("serverDiskName", serverDisk.getOrDefault("name", "应用运行磁盘"));
        result.put("serverDiskDescription", serverDisk.getOrDefault("description", ""));
        result.put("serverDiskPath", serverDisk.getOrDefault("path", Paths.get("").toAbsolutePath().toString()));

        result.put("quotaAllocatedBytes", quotaAllocatedBytes);
        result.put("activeFileBytes", activeFileBytes);
        result.put("deletedFileBytes", deletedFileBytes);
        result.put("allStoredFileBytes", activeFileBytes + deletedFileBytes);
        result.put("quotaUsagePercent", percent(activeFileBytes, quotaAllocatedBytes));
        result.put("serverLogicalUsagePercent", percent(activeFileBytes, serverTotalBytes));
        result.put("quotaOversoldBytes", Math.max(0L, quotaAllocatedBytes - serverTotalBytes));
        result.put("activeFileCount", activeFileCount);
        result.put("activeFolderCount", activeFolderCount);
        result.put("deletedFileCount", deletedFileCount);
        result.put("userCount", userCount);
        result.put("diskStores", diskStores());
        result.put("note", "服务器容量读取自 Spring Boot 应用所在服务器的文件系统；MinIO 使用 Docker 卷时，通常与 Docker Desktop/宿主机磁盘容量相关。业务已用空间按数据库记录的未删除文件大小统计。");
        return result;
    }

    private Map<String, Object> currentServerDisk() {
        Path appPath = Paths.get("").toAbsolutePath();
        try {
            return fileStoreMap(Files.getFileStore(appPath), appPath.toString(), true);
        } catch (IOException e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("name", "未知磁盘");
            fallback.put("description", e.getMessage());
            fallback.put("path", appPath.toString());
            fallback.put("totalBytes", 0L);
            fallback.put("usedBytes", 0L);
            fallback.put("usableBytes", 0L);
            fallback.put("freeBytes", 0L);
            fallback.put("usedPercent", 0D);
            fallback.put("primary", true);
            return fallback;
        }
    }

    private List<Map<String, Object>> diskStores() {
        List<Map<String, Object>> stores = new ArrayList<>();
        String currentName = String.valueOf(currentServerDisk().getOrDefault("name", ""));
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            try {
                Map<String, Object> item = fileStoreMap(store, "", String.valueOf(store).equals(currentName));
                if (longFromMap(item, "totalBytes") > 0) stores.add(item);
            } catch (IOException ignored) {
                // 某些系统盘/虚拟盘可能没有读取权限，忽略即可，避免影响管理后台打开。
            }
        }
        stores.sort((a, b) -> Long.compare(longFromMap(b, "totalBytes"), longFromMap(a, "totalBytes")));
        return stores;
    }

    private Map<String, Object> fileStoreMap(FileStore store, String path, boolean primary) throws IOException {
        long total = Math.max(0L, store.getTotalSpace());
        long usable = Math.max(0L, store.getUsableSpace());
        long free = Math.max(0L, store.getUnallocatedSpace());
        long used = Math.max(0L, total - usable);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", String.valueOf(store));
        map.put("description", store.name() + " / " + store.type());
        map.put("path", path == null ? "" : path);
        map.put("totalBytes", total);
        map.put("usedBytes", used);
        map.put("usableBytes", usable);
        map.put("freeBytes", free);
        map.put("usedPercent", percent(used, total));
        map.put("usablePercent", percent(usable, total));
        map.put("primary", primary);
        return map;
    }

    private double percent(long part, long total) {
        if (total <= 0) return 0D;
        return Math.round((part * 10000D / total)) / 100D;
    }

    private long longFromMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String storageStatus(double serverUsablePercent) {
        if (serverUsablePercent <= 10D) return "DANGER";
        if (serverUsablePercent <= 20D) return "WARNING";
        return "NORMAL";
    }

    private String storageStatusText(String status) {
        if ("DANGER".equals(status)) return "容量紧张，建议尽快扩容或清理回收站";
        if ("WARNING".equals(status)) return "剩余容量偏低，需要关注";
        return "容量充足";
    }

    private Map<String, Object> toAdminUserMap(AppUser currentAdmin, AppUser user) {
        long used = fileService.usedBytes(user.getId());
        long quota = user.getQuotaBytes() == null ? 0L : user.getQuotaBytes();
        long abnormalCount = fileRepository.findByOwnerId(user.getId()).stream()
                .filter(f -> "ABNORMAL".equalsIgnoreCase(f.getReviewStatus()))
                .count();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("role", user.getRole());
        map.put("permissionLevel", user.getRole());
        map.put("enabled", user.getEnabled());
        map.put("quotaBytes", quota);
        map.put("usedBytes", used);
        map.put("remainingBytes", Math.max(0L, quota - used));
        map.put("elasticExtraBytes", quota / 2);
        map.put("effectiveUploadLimitBytes", quota + quota / 2);
        map.put("createdAt", user.getCreatedAt());
        map.put("status", abnormalCount > 0 ? "异常" : "正常");
        map.put("abnormalCount", abnormalCount);
        map.put("isSelf", currentAdmin != null && currentAdmin.getId().equals(user.getId()));
        return map;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
