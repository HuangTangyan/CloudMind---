package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.CreateFolderRequest;
import com.cloudmind.demo.dto.MoveCopyRequest;
import com.cloudmind.demo.dto.RenameRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import com.cloudmind.demo.service.FileService;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final AuthService authService;
    private final FileService fileService;

    public FileController(AuthService authService, FileService fileService) {
        this.authService = authService;
        this.fileService = fileService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "X-Token", required = false) String token,
                                    @RequestParam(required = false) Long parentId) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.list(user, parentId));
    }

    @GetMapping("/folders")
    public Map<String, Object> folders(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.allFolders(user));
    }

    @GetMapping("/trash")
    public Map<String, Object> trash(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.trash(user));
    }

    @GetMapping("/gallery")
    public Map<String, Object> gallery(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.gallery(user));
    }

    @PostMapping("/folder")
    public Map<String, Object> createFolder(@RequestHeader(value = "X-Token", required = false) String token,
                                            @Valid @RequestBody CreateFolderRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "文件夹创建成功", "data", fileService.createFolder(user, request.getParentId(), request.getName()));
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestHeader(value = "X-Token", required = false) String token,
                                      @RequestParam(required = false) Long parentId,
                                      @RequestPart("file") MultipartFile file) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "上传成功", "data", fileService.upload(user, parentId, file));
    }


    @PostMapping("/upload-folder")
    public Map<String, Object> uploadFolder(@RequestHeader(value = "X-Token", required = false) String token,
                                            @RequestParam(required = false) Long parentId,
                                            @RequestPart("files") List<MultipartFile> files,
                                            @RequestParam(value = "relativePaths", required = false) List<String> relativePaths) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "文件夹上传成功", "data", fileService.uploadFolder(user, parentId, files, relativePaths));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@RequestHeader(value = "X-Token", required = false) String headerToken,
                                                        @RequestParam(value = "token", required = false) String queryToken,
                                                        @RequestParam(value = "disposition", required = false, defaultValue = "attachment") String disposition,
                                                        @PathVariable Long id) {
        String token = headerToken != null ? headerToken : queryToken;
        AppUser user = authService.requireUser(token);
        return fileService.download(user, id, "inline".equalsIgnoreCase(disposition));
    }

    @GetMapping("/{id}/preview")
    public Map<String, Object> preview(@RequestHeader(value = "X-Token", required = false) String headerToken,
                                       @RequestParam(value = "token", required = false) String queryToken,
                                       @PathVariable Long id) {
        String token = headerToken != null ? headerToken : queryToken;
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.preview(user, id, token));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@RequestHeader(value = "X-Token", required = false) String token,
                                      @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.detail(user, id));
    }

    @PostMapping("/{id}/analyze")
    public Map<String, Object> analyze(@RequestHeader(value = "X-Token", required = false) String token,
                                       @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "摘要和标签已重新生成", "data", fileService.reanalyze(user, id));
    }

    @GetMapping("/{id}/related")
    public Map<String, Object> related(@RequestHeader(value = "X-Token", required = false) String token,
                                       @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        List<Map<String, Object>> list = fileService.related(user, id);
        return Map.of("success", true, "data", list);
    }

    @GetMapping("/{id}/versions")
    public Map<String, Object> versions(@RequestHeader(value = "X-Token", required = false) String token,
                                        @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", fileService.versions(user, id));
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public Map<String, Object> restoreVersion(@RequestHeader(value = "X-Token", required = false) String token,
                                              @PathVariable Long id,
                                              @PathVariable Long versionId) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "历史版本恢复成功", "data", fileService.restoreVersion(user, id, versionId));
    }

    @PutMapping("/{id}/rename")
    public Map<String, Object> rename(@RequestHeader(value = "X-Token", required = false) String token,
                                      @PathVariable Long id,
                                      @Valid @RequestBody RenameRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "重命名成功", "data", fileService.rename(user, id, request.getName()));
    }

    @PutMapping("/{id}/move")
    public Map<String, Object> move(@RequestHeader(value = "X-Token", required = false) String token,
                                    @PathVariable Long id,
                                    @RequestBody MoveCopyRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "移动成功", "data", fileService.move(user, id, request.effectiveParentId()));
    }

    @PostMapping("/{id}/copy")
    public Map<String, Object> copy(@RequestHeader(value = "X-Token", required = false) String token,
                                    @PathVariable Long id,
                                    @RequestBody MoveCopyRequest request) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "message", "复制成功", "data", fileService.copy(user, id, request.effectiveParentId()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestHeader(value = "X-Token", required = false) String token,
                                      @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        fileService.delete(user, id);
        return Map.of("success", true, "message", "已移动到回收站");
    }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@RequestHeader(value = "X-Token", required = false) String token,
                                       @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        fileService.restore(user, id);
        return Map.of("success", true, "message", "已恢复");
    }

    @DeleteMapping("/{id}/permanent")
    public Map<String, Object> permanentDelete(@RequestHeader(value = "X-Token", required = false) String token,
                                               @PathVariable Long id) {
        AppUser user = authService.requireUser(token);
        fileService.purge(user, id);
        return Map.of("success", true, "message", "已永久删除");
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestHeader(value = "X-Token", required = false) String token,
                                      @RequestParam String q) {
        AppUser user = authService.requireUser(token);
        List<Map<String, Object>> list = fileService.search(user, q);
        return Map.of("success", true, "data", list);
    }
}
