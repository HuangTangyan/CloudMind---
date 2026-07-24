package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.entity.InviteAuditLog;
import com.cloudmind.demo.repository.InviteAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InviteAuditService {
    private final InviteAuditLogRepository repository;

    public InviteAuditService(InviteAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AppUser actor,
            String action,
            String batchNo,
            String codeFingerprint,
            String result,
            String reason,
            String clientIp,
            String userAgent
    ) {
        InviteAuditLog log = new InviteAuditLog();
        log.setActorId(actor == null ? null : actor.getId());
        log.setActorUsername(safe(actor == null ? "unknown" : actor.getUsername(), 64));
        log.setAction(safe(action, 40));
        log.setBatchNo(safeNullable(batchNo, 48));
        log.setCodeFingerprint(safeNullable(codeFingerprint, 16));
        log.setResult(safe(result, 16));
        log.setReason(safeNullable(reason, 200));
        log.setClientIp(safeNullable(clientIp, 64));
        log.setUserAgent(safeNullable(userAgent, 255));
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(AppUser admin, AuthService authService) {
        if (admin == null || !authService.isAdmin(admin)) {
            throw new SecurityException("需要管理员权限");
        }
        return repository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(this::toMap)
                .toList();
    }

    private Map<String, Object> toMap(InviteAuditLog log) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", log.getId());
        result.put("actor", log.getActorUsername());
        result.put("action", log.getAction());
        result.put("batchNo", log.getBatchNo() == null ? "" : log.getBatchNo());
        result.put("codeFingerprint", log.getCodeFingerprint() == null ? "" : log.getCodeFingerprint());
        result.put("result", log.getResult());
        result.put("reason", log.getReason() == null ? "" : log.getReason());
        result.put("clientIp", log.getClientIp() == null ? "" : log.getClientIp());
        result.put("userAgent", log.getUserAgent() == null ? "" : log.getUserAgent());
        result.put("createdAt", log.getCreatedAt());
        return result;
    }

    private String safe(String value, int maxLength) {
        String cleaned = sanitize(value);
        return cleaned.isBlank() ? "-" : truncate(cleaned, maxLength);
    }

    private String safeNullable(String value, int maxLength) {
        String cleaned = sanitize(value);
        return cleaned.isBlank() ? null : truncate(cleaned, maxLength);
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
