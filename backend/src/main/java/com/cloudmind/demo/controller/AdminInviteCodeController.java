package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.CreateInviteBatchRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import com.cloudmind.demo.service.InviteCodeService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/invite-batches")
public class AdminInviteCodeController {
    private final AuthService authService;
    private final InviteCodeService inviteCodeService;

    public AdminInviteCodeController(
            AuthService authService,
            InviteCodeService inviteCodeService
    ) {
        this.authService = authService;
        this.inviteCodeService = inviteCodeService;
    }

    @GetMapping
    public Map<String, Object> batches(
            @RequestHeader(value = "X-Token", required = false) String token
    ) {
        AppUser admin = authService.requireAdmin(token);
        return Map.of(
                "success", true,
                "data", inviteCodeService.listBatches(admin)
        );
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> createAndExport(
            @RequestHeader(value = "X-Token", required = false) String token,
            @RequestParam(defaultValue = "txt") String format,
            @Valid @RequestBody CreateInviteBatchRequest request
    ) {
        AppUser admin = authService.requireAdmin(token);
        InviteCodeService.InviteBatchExport export =
                inviteCodeService.createBatchExport(admin, request, format);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(export.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(export.contentType()))
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Invite-Batch", export.batchNo())
                .body(export.content());
    }
}
