package com.cloudmind.demo.controller;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AdminService;
import com.cloudmind.demo.service.AiConfigService;
import com.cloudmind.demo.service.AuthService;
import com.cloudmind.demo.service.FileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AuthService authService;
    private final AdminService adminService;
    private final FileService fileService;
    private final AiConfigService aiConfigService;

    public AdminController(AuthService authService,
                           AdminService adminService,
                           FileService fileService,
                           AiConfigService aiConfigService) {
        this.authService = authService;
        this.adminService = adminService;
        this.fileService = fileService;
        this.aiConfigService = aiConfigService;
    }

    @GetMapping("/users")
    public Map<String, Object> users(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser admin = authService.requireAdmin(token);
        return Map.of("success", true, "data", adminService.users(admin));
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestHeader(value = "X-Token", required = false) String token,
                                          @RequestBody Map<String, Object> body) {
        AppUser admin = authService.requireAdmin(token);
        return Map.of("success", true, "message", "用户创建成功", "data", adminService.createUser(admin, body));
    }

    @PutMapping("/users/{userId}")
    public Map<String, Object> updateUser(@RequestHeader(value = "X-Token", required = false) String token,
                                          @PathVariable Long userId,
                                          @RequestBody Map<String, Object> body) {
        AppUser admin = authService.requireAdmin(token);
        return Map.of("success", true, "message", "用户信息已更新", "data", adminService.updateUser(admin, userId, body));
    }

    @PostMapping("/users/{userId}/reset-password")
    public Map<String, Object> resetPassword(@RequestHeader(value = "X-Token", required = false) String token,
                                             @PathVariable Long userId) {
        AppUser admin = authService.requireAdmin(token);
        String newPassword = adminService.resetPassword(admin, userId);
        return Map.of("success", true, "message", "密码已重置", "data", Map.of("newPassword", newPassword));
    }

    @PostMapping("/users/{userId}/enabled")
    public Map<String, Object> setEnabled(@RequestHeader(value = "X-Token", required = false) String token,
                                          @PathVariable Long userId,
                                          @RequestBody Map<String, Object> body) {
        AppUser admin = authService.requireAdmin(token);
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", "true")));
        return Map.of("success", true, "message", enabled ? "账号已解封" : "账号已封禁", "data", adminService.setEnabled(admin, userId, enabled));
    }

    @DeleteMapping("/users/{userId}")
    public Map<String, Object> deleteUser(@RequestHeader(value = "X-Token", required = false) String token,
                                          @PathVariable Long userId) {
        AppUser admin = authService.requireAdmin(token);
        adminService.deleteUser(admin, userId);
        return Map.of("success", true, "message", "用户及其文件已删除");
    }

    @GetMapping("/audit/users")
    public Map<String, Object> auditUsers(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser admin = authService.requireAdmin(token);
        return Map.of("success", true, "data", adminService.auditUsers(admin));
    }

    @GetMapping("/audit/users/{ownerId}/files")
    public Map<String, Object> auditFiles(@RequestHeader(value = "X-Token", required = false) String token,
                                          @PathVariable Long ownerId,
                                          @RequestParam(required = false) Long parentId) {
        authService.requireAdmin(token);
        return Map.of("success", true, "data", fileService.adminListFiles(ownerId, parentId));
    }

    @GetMapping("/files/{fileId}/preview")
    public Map<String, Object> preview(@RequestHeader(value = "X-Token", required = false) String headerToken,
                                       @RequestParam(value = "token", required = false) String queryToken,
                                       @PathVariable Long fileId) {
        String token = headerToken != null ? headerToken : queryToken;
        authService.requireAdmin(token);
        return Map.of("success", true, "data", fileService.adminPreview(fileId, token));
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@RequestHeader(value = "X-Token", required = false) String headerToken,
                                                        @RequestParam(value = "token", required = false) String queryToken,
                                                        @RequestParam(value = "disposition", required = false, defaultValue = "attachment") String disposition,
                                                        @PathVariable Long fileId) {
        String token = headerToken != null ? headerToken : queryToken;
        authService.requireAdmin(token);
        return fileService.adminDownload(fileId, "inline".equalsIgnoreCase(disposition));
    }

    @PutMapping("/files/{fileId}/review")
    public Map<String, Object> review(@RequestHeader(value = "X-Token", required = false) String token,
                                      @PathVariable Long fileId,
                                      @RequestBody Map<String, Object> body) {
        authService.requireAdmin(token);
        String status = body.get("status") == null ? null : String.valueOf(body.get("status"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        return Map.of("success", true, "message", "审查状态已更新", "data", fileService.adminUpdateReview(fileId, status, note));
    }


    @PostMapping("/files/{fileId}/analyze")
    public Map<String, Object> reanalyze(@RequestHeader(value = "X-Token", required = false) String token,
                                         @PathVariable Long fileId) {
        authService.requireAdmin(token);
        return Map.of("success", true, "message", "摘要和标签已重新生成", "data", fileService.adminReanalyze(fileId));
    }


    @GetMapping("/storage/overview")
    public Map<String, Object> storageOverview(@RequestHeader(value = "X-Token", required = false) String token) {
        authService.requireAdmin(token);
        return Map.of("success", true, "data", adminService.storageOverview());
    }

    @GetMapping("/ai/config")
    public Map<String, Object> aiConfig(@RequestHeader(value = "X-Token", required = false) String token) {
        authService.requireAdmin(token);
        return Map.of("success", true, "data", aiConfigService.getConfigForAdmin());
    }

    @PutMapping("/ai/config")
    public Map<String, Object> saveAiConfig(@RequestHeader(value = "X-Token", required = false) String token,
                                            @RequestBody Map<String, Object> body) {
        authService.requireAdmin(token);
        return Map.of("success", true, "message", "AI 接口配置已保存", "data", aiConfigService.saveConfig(body));
    }

    @PostMapping("/ai/test")
    public Map<String, Object> testAi(@RequestHeader(value = "X-Token", required = false) String token) {
        authService.requireAdmin(token);
        return Map.of("success", true, "data", aiConfigService.testConnection());
    }

    @PostMapping("/files/{fileId}/ai-review")
    public Map<String, Object> aiReview(@RequestHeader(value = "X-Token", required = false) String token,
                                        @PathVariable Long fileId) {
        authService.requireAdmin(token);
        return Map.of("success", true, "message", "AI/规则审查完成", "data", aiConfigService.aiReviewFile(fileId));
    }

    @PostMapping("/ai/review-all")
    public Map<String, Object> aiReviewAll(@RequestHeader(value = "X-Token", required = false) String token) {
        authService.requireAdmin(token);
        return Map.of("success", true, "message", "全站 AI/规则审查完成", "data", aiConfigService.aiReviewAll());
    }

    @PostMapping("/ai/review-pending")
    public Map<String, Object> aiReviewPending(@RequestHeader(value = "X-Token", required = false) String token) {
        authService.requireAdmin(token);
        return Map.of("success", true, "message", "未审查文件 AI/规则审查完成", "data", aiConfigService.aiReviewPending());
    }

}