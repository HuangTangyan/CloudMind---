package com.cloudmind.demo.service;

import com.cloudmind.demo.dto.CreateInviteBatchRequest;
import com.cloudmind.demo.dto.RevokeInviteBatchRequest;
import com.cloudmind.demo.dto.RevokeInviteCodeRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.entity.InviteCode;
import com.cloudmind.demo.entity.InviteCodeBatch;
import com.cloudmind.demo.repository.InviteCodeBatchRepository;
import com.cloudmind.demo.repository.InviteCodeRepository;
import com.cloudmind.demo.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class InviteCodeService {
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final DateTimeFormatter BATCH_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int RANDOM_CODE_LENGTH = 26;
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final InviteCodeBatchRepository batchRepository;
    private final InviteCodeRepository codeRepository;
    private final AuthService authService;
    private final AppUserRepository userRepository;
    private final MembershipEntitlementService membershipEntitlementService;
    private final InviteAuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${cloudmind.invites.max-batch-size:500}")
    private int maxBatchSize = 500;

    @Value("${cloudmind.invites.daily-generation-limit:2000}")
    private int dailyGenerationLimit = 2000;

    public InviteCodeService(
            InviteCodeBatchRepository batchRepository,
            InviteCodeRepository codeRepository,
            AuthService authService,
            AppUserRepository userRepository,
            MembershipEntitlementService membershipEntitlementService,
            InviteAuditService auditService
    ) {
        this.batchRepository = batchRepository;
        this.codeRepository = codeRepository;
        this.authService = authService;
        this.userRepository = userRepository;
        this.membershipEntitlementService = membershipEntitlementService;
        this.auditService = auditService;
    }

    @Transactional
    public InviteBatchExport createBatchExport(
            AppUser admin,
            CreateInviteBatchRequest request,
            String requestedFormat
    ) {
        return createBatchExport(admin, request, requestedFormat, null, null);
    }

    @Transactional
    public InviteBatchExport createBatchExport(
            AppUser admin,
            CreateInviteBatchRequest request,
            String requestedFormat,
            String clientIp,
            String userAgent
    ) {
        String role = null;
        String batchNo = null;
        try {
            AppUser lockedAdmin = lockAdmin(admin);
            authService.reauthenticateAdmin(lockedAdmin, request.getCurrentPassword());
            role = normalizeTargetRole(request.getRole());
            String format = normalizeFormat(requestedFormat);
            int configuredLimit = Math.max(1, Math.min(500, maxBatchSize));
            if (request.getCount() < 1 || request.getCount() > configuredLimit) {
                throw new IllegalArgumentException("单批邀请码数量应为 1-" + configuredLimit);
            }
            if (request.getMembershipDays() < 1 || request.getMembershipDays() > 365) {
                throw new IllegalArgumentException("会员期限应为 1-365 天");
            }

            Instant now = Instant.now();
            Instant expiresAt = request.getExpiresAt();
            if (expiresAt == null || !expiresAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
                throw new IllegalArgumentException("邀请码有效期至少应晚于当前时间 5 分钟");
            }
            if (expiresAt.isAfter(now.plus(Duration.ofDays(365)))) {
                throw new IllegalArgumentException("邀请码有效期不能超过 365 天");
            }
            DailyWindow window = dailyWindow();
            long alreadyGenerated = batchRepository.sumGeneratedByAdminBetween(
                    lockedAdmin.getId(),
                    window.start(),
                    window.end()
            );
            int configuredDailyLimit = Math.max(1, dailyGenerationLimit);
            if (alreadyGenerated + request.getCount() > configuredDailyLimit) {
                throw new IllegalArgumentException(
                        "今日邀请码生成量已达到安全上限，剩余可生成 "
                                + Math.max(0L, configuredDailyLimit - alreadyGenerated) + " 个"
                );
            }

            InviteCodeBatch batch = new InviteCodeBatch();
            batchNo = newBatchNo(now);
            batch.setBatchNo(batchNo);
            batch.setTargetRole(role);
            batch.setMembershipDays(request.getMembershipDays());
            batch.setTotalCount(request.getCount());
            batch.setExpiresAt(expiresAt);
            batch.setNote(normalizeNote(request.getNote()));
            batch.setExportFormat(format.toUpperCase(Locale.ROOT));
            batch.setCreatedBy(lockedAdmin);
            batch.setCreatedAt(now);
            batch = batchRepository.save(batch);

            List<String> plainCodes = new ArrayList<>(request.getCount());
            List<InviteCode> storedCodes = new ArrayList<>(request.getCount());
            Set<String> hashes = new HashSet<>();
            while (plainCodes.size() < request.getCount()) {
                String displayCode = generateDisplayCode();
                String codeHash = hashCanonicalCode(canonicalCode(displayCode));
                if (!hashes.add(codeHash) || codeRepository.existsByCodeHash(codeHash)) {
                    continue;
                }
                InviteCode code = new InviteCode();
                code.setBatch(batch);
                code.setCodeHash(codeHash);
                code.setCreatedAt(now);
                plainCodes.add(displayCode);
                storedCodes.add(code);
            }
            codeRepository.saveAll(storedCodes);

            byte[] content = "csv".equals(format)
                    ? csvContent(batch, plainCodes)
                    : txtContent(plainCodes);
            String extension = "csv".equals(format) ? "csv" : "txt";
            String contentType = "csv".equals(format)
                    ? "text/csv;charset=UTF-8"
                    : "text/plain;charset=UTF-8";
            String filename = "cloudmind-" + role.toLowerCase(Locale.ROOT)
                    + "-" + request.getMembershipDays() + "d"
                    + "-" + batch.getBatchNo().toLowerCase(Locale.ROOT)
                    + "." + extension;
            auditService.record(
                    lockedAdmin, "CREATE_EXPORT", batchNo, null, "SUCCESS",
                    "生成 " + request.getCount() + " 个 " + role
                            + " 邀请码；会员期限 " + request.getMembershipDays() + " 天",
                    clientIp, userAgent
            );
            return new InviteBatchExport(
                    filename,
                    contentType,
                    content,
                    batch.getBatchNo(),
                    request.getCount()
            );
        } catch (RuntimeException ex) {
            auditService.record(
                    admin, "CREATE_EXPORT", batchNo, null, "FAILED",
                    safeAuditReason(ex), clientIp, userAgent
            );
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBatches(AppUser admin) {
        return listBatches(admin, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBatches(
            AppUser admin,
            String query,
            String role,
            String status
    ) {
        if (admin == null || !authService.isAdmin(admin)) {
            throw new SecurityException("需要管理员权限");
        }
        Instant now = Instant.now();
        String normalizedQuery = normalizeBatchQuery(query);
        String normalizedRole = normalizeBatchRoleFilter(role);
        String normalizedStatus = normalizeBatchStatusFilter(status);
        return batchRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(batch -> matchesBatchQuery(batch, normalizedQuery))
                .filter(batch -> normalizedRole.isBlank()
                        || normalizedRole.equalsIgnoreCase(batch.getTargetRole()))
                .filter(batch -> normalizedStatus.isBlank()
                        || normalizedStatus.equals(batchStatus(batch, now)))
                .map(batch -> toBatchMap(batch, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> batchCodes(AppUser admin, Long batchId) {
        if (admin == null || !authService.isAdmin(admin)) {
            throw new SecurityException("需要管理员权限");
        }
        InviteCodeBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("邀请码批次不存在"));
        Instant now = Instant.now();
        List<Map<String, Object>> codes = codeRepository
                .findTop200ByBatch_IdOrderByIdAsc(batchId)
                .stream()
                .map(code -> toCodeMap(code, batch, now))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", toBatchMap(batch, now));
        result.put("codes", codes);
        result.put("truncated", batch.getTotalCount() != null
                && batch.getTotalCount() > codes.size());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(AppUser admin) {
        if (admin == null || !authService.isAdmin(admin)) {
            throw new SecurityException("需要管理员权限");
        }
        DailyWindow window = dailyWindow();
        long generated = batchRepository.sumGeneratedByAdminBetween(
                admin.getId(),
                window.start(),
                window.end()
        );
        int limit = Math.max(1, dailyGenerationLimit);
        return Map.of(
                "date", window.date(),
                "dailyGenerated", generated,
                "dailyLimit", limit,
                "dailyRemaining", Math.max(0L, limit - generated)
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> audit(AppUser admin) {
        return auditService.recent(admin, authService);
    }

    @Transactional
    public Map<String, Object> revokeCode(
            AppUser admin,
            RevokeInviteCodeRequest request,
            String clientIp,
            String userAgent
    ) {
        String fingerprint = null;
        try {
            AppUser lockedAdmin = lockAdmin(admin);
            authService.reauthenticateAdmin(lockedAdmin, request.getCurrentPassword());
            String codeHash = hashCanonicalCode(canonicalCode(request.getCode()));
            fingerprint = fingerprint(codeHash);
            InviteCode code = codeRepository.findForUpdateByCodeHash(codeHash)
                    .orElseThrow(this::unavailableCode);
            if (code.getRedeemedAt() != null || code.getRevokedAt() != null) {
                throw unavailableCode();
            }
            Instant now = Instant.now();
            String reason = requireReason(request.getReason());
            code.setRevokedAt(now);
            code.setRevokedBy(lockedAdmin);
            code.setRevokeReason(reason);
            codeRepository.save(code);
            String batchNo = code.getBatch().getBatchNo();
            auditService.record(
                    lockedAdmin, "REVOKE_CODE", batchNo, fingerprint, "SUCCESS",
                    reason, clientIp, userAgent
            );
            return Map.of(
                    "batchNo", batchNo,
                    "codeFingerprint", fingerprint,
                    "revokedAt", now
            );
        } catch (RuntimeException ex) {
            auditService.record(
                    admin, "REVOKE_CODE", null, fingerprint, "FAILED",
                    safeAuditReason(ex), clientIp, userAgent
            );
            throw ex;
        }
    }

    @Transactional
    public Map<String, Object> revokeBatch(
            AppUser admin,
            Long batchId,
            RevokeInviteBatchRequest request,
            String clientIp,
            String userAgent
    ) {
        String batchNo = null;
        try {
            AppUser lockedAdmin = lockAdmin(admin);
            authService.reauthenticateAdmin(lockedAdmin, request.getCurrentPassword());
            InviteCodeBatch batch = batchRepository.findForUpdateById(batchId)
                    .orElseThrow(() -> new IllegalArgumentException("邀请码批次不存在"));
            batchNo = batch.getBatchNo();
            if (batch.getRevokedAt() != null) {
                throw new IllegalArgumentException("该邀请码批次已经撤销");
            }
            Instant now = Instant.now();
            String reason = requireReason(request.getReason());
            batch.setRevokedAt(now);
            batch.setRevokedBy(lockedAdmin);
            batch.setRevokeReason(reason);
            batchRepository.save(batch);
            int revokedCount = codeRepository.revokeUnusedByBatch(
                    batch.getId(), now, lockedAdmin, reason
            );
            auditService.record(
                    lockedAdmin, "REVOKE_BATCH", batchNo, null, "SUCCESS",
                    reason + "；撤销未使用邀请码 " + revokedCount + " 个",
                    clientIp, userAgent
            );
            return Map.of(
                    "batchNo", batchNo,
                    "revokedCount", revokedCount,
                    "revokedAt", now
            );
        } catch (RuntimeException ex) {
            auditService.record(
                    admin, "REVOKE_BATCH", batchNo, null, "FAILED",
                    safeAuditReason(ex), clientIp, userAgent
            );
            throw ex;
        }
    }

    @Transactional
    public Map<String, Object> redeem(String accessToken, String plainCode) {
        return redeem(accessToken, plainCode, null, null);
    }

    @Transactional
    public Map<String, Object> redeem(
            String accessToken,
            String plainCode,
            String clientIp,
            String userAgent
    ) {
        AppUser user = null;
        String batchNo = null;
        String codeFingerprint = null;
        try {
            user = authService.requireUser(accessToken);
            String codeHash = hashCanonicalCode(canonicalCode(plainCode));
            codeFingerprint = fingerprint(codeHash);
            InviteCode code = codeRepository.findForUpdateByCodeHash(codeHash)
                    .orElseThrow(this::unavailableCode);
            InviteCodeBatch batch = code.getBatch();
            batchNo = batch.getBatchNo();
            Instant now = Instant.now();
            if (code.getRedeemedAt() != null
                    || code.getRevokedAt() != null
                    || batch.getRevokedAt() != null
                    || batch.getExpiresAt() == null
                    || !batch.getExpiresAt().isAfter(now)) {
                throw unavailableCode();
            }

            String targetRole = normalizeTargetRole(batch.getTargetRole());
            int membershipDays = normalizeMembershipDays(batch.getMembershipDays());
            boolean activeTemporaryMembership = user.getMembershipExpiresAt() != null
                    && user.getMembershipExpiresAt().isAfter(now);
            int currentRank = membershipRank(user.getRole());
            int targetRank = membershipRank(targetRole);
            if (currentRank > targetRank || currentRank == targetRank && !activeTemporaryMembership) {
                throw unavailableCode();
            }

            long targetQuota = membershipEntitlementService.storageQuotaBytes(targetRole);
            Map<String, Object> session = authService.upgradeMembershipAndRotateSession(
                    accessToken,
                    user,
                    targetRole,
                    targetQuota,
                    membershipDays
            );
            code.setRedeemedBy(user);
            code.setRedeemedAt(now);
            codeRepository.saveAndFlush(code);

            auditService.record(
                    user, "REDEEM", batchNo, codeFingerprint, "SUCCESS",
                    "兑换 " + targetRole + "；会员期限 " + membershipDays + " 天",
                    clientIp, userAgent
            );
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetRole", targetRole);
            result.put("membershipDays", membershipDays);
            result.put("membershipExpiresAt", user.getMembershipExpiresAt());
            result.put("batchNo", batchNo);
            result.put("redeemedAt", now);
            result.put("session", session);
            return result;
        } catch (RuntimeException ex) {
            auditService.record(
                    user, "REDEEM", batchNo, codeFingerprint, "FAILED",
                    safeAuditReason(ex), clientIp, userAgent
            );
            throw ex;
        }
    }

    private Map<String, Object> toBatchMap(InviteCodeBatch batch, Instant now) {
        long redeemedCount = batch.getId() == null
                ? 0L
                : codeRepository.countByBatch_IdAndRedeemedAtIsNotNull(batch.getId());
        long revokedCount = batch.getId() == null
                ? 0L
                : codeRepository.countByBatch_IdAndRevokedAtIsNotNull(batch.getId());
        int totalCount = batch.getTotalCount() == null ? 0 : batch.getTotalCount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.getId());
        result.put("batchNo", batch.getBatchNo());
        result.put("targetRole", batch.getTargetRole());
        result.put("membershipDays", normalizeMembershipDays(batch.getMembershipDays()));
        result.put("totalCount", totalCount);
        result.put("redeemedCount", redeemedCount);
        result.put("revokedCount", revokedCount);
        result.put("remainingCount", Math.max(0L, totalCount - redeemedCount - revokedCount));
        result.put("status", batchStatus(batch, now));
        result.put("expiresAt", batch.getExpiresAt());
        result.put("note", batch.getNote() == null ? "" : batch.getNote());
        result.put("exportFormat", batch.getExportFormat());
        result.put("createdAt", batch.getCreatedAt());
        result.put("createdBy", batch.getCreatedBy() == null
                ? "已删除账号"
                : batch.getCreatedBy().getUsername());
        result.put("revokedAt", batch.getRevokedAt());
        result.put("revokeReason", batch.getRevokeReason() == null ? "" : batch.getRevokeReason());
        return result;
    }

    private Map<String, Object> toCodeMap(
            InviteCode code,
            InviteCodeBatch batch,
            Instant now
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", code.getId());
        result.put("codeFingerprint", fingerprint(code.getCodeHash()));
        result.put("status", codeStatus(code, batch, now));
        result.put("redeemedBy", maskedUsername(code.getRedeemedBy()));
        result.put("redeemedAt", code.getRedeemedAt());
        result.put("revokedAt", code.getRevokedAt());
        result.put("revokeReason", code.getRevokeReason() == null ? "" : code.getRevokeReason());
        result.put("createdAt", code.getCreatedAt());
        return result;
    }

    private String batchStatus(InviteCodeBatch batch, Instant now) {
        if (batch.getRevokedAt() != null) return "REVOKED";
        return batch.getExpiresAt() != null && batch.getExpiresAt().isAfter(now)
                ? "ACTIVE"
                : "EXPIRED";
    }

    private String codeStatus(InviteCode code, InviteCodeBatch batch, Instant now) {
        if (code.getRedeemedAt() != null) return "REDEEMED";
        if (code.getRevokedAt() != null || batch.getRevokedAt() != null) return "REVOKED";
        return batch.getExpiresAt() != null && batch.getExpiresAt().isAfter(now)
                ? "AVAILABLE"
                : "EXPIRED";
    }

    private String maskedUsername(AppUser user) {
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) return "";
        String value = user.getUsername().trim();
        if (value.length() <= 2) return value.substring(0, 1) + "*";
        if (value.length() <= 5) return value.substring(0, 1) + "***";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String normalizeBatchQuery(String query) {
        if (query == null || query.isBlank()) return "";
        String value = query.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return value.length() <= 80 ? value : value.substring(0, 80);
    }

    private String normalizeBatchRoleFilter(String role) {
        if (role == null || role.isBlank() || "ALL".equalsIgnoreCase(role)) return "";
        return normalizeTargetRole(role);
    }

    private String normalizeBatchStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return "";
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "EXPIRED", "REVOKED").contains(value)) {
            throw new IllegalArgumentException("批次状态筛选值无效");
        }
        return value;
    }

    private boolean matchesBatchQuery(InviteCodeBatch batch, String query) {
        if (query.isBlank()) return true;
        String batchNo = batch.getBatchNo() == null
                ? ""
                : batch.getBatchNo().toLowerCase(Locale.ROOT);
        String note = batch.getNote() == null
                ? ""
                : batch.getNote().toLowerCase(Locale.ROOT);
        return batchNo.contains(query) || note.contains(query);
    }

    private AppUser lockAdmin(AppUser admin) {
        if (admin == null || admin.getId() == null || !authService.isAdmin(admin)) {
            throw new SecurityException("需要管理员权限");
        }
        AppUser locked = userRepository.findForUpdateById(admin.getId())
                .orElseThrow(() -> new SecurityException("管理员账号不存在或已失效"));
        if (!Boolean.TRUE.equals(locked.getEnabled()) || !authService.isAdmin(locked)) {
            throw new SecurityException("管理员账号不存在或已失效");
        }
        return locked;
    }

    private DailyWindow dailyWindow() {
        ZonedDateTime start = ZonedDateTime.now(CAMPUS_ZONE)
                .toLocalDate()
                .atStartOfDay(CAMPUS_ZONE);
        return new DailyWindow(
                start.toLocalDate().toString(),
                start.toInstant(),
                start.plusDays(1).toInstant()
        );
    }

    private String requireReason(String reason) {
        String value = normalizeNote(reason);
        if (value.isBlank()) throw new IllegalArgumentException("撤销原因不能为空");
        return value;
    }

    private String fingerprint(String codeHash) {
        return codeHash == null || codeHash.length() < 12 ? "" : codeHash.substring(0, 12);
    }

    private String safeAuditReason(RuntimeException ex) {
        String value = ex.getMessage();
        if (value == null || value.isBlank()) return ex.getClass().getSimpleName();
        value = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    private byte[] txtContent(List<String> plainCodes) {
        String content = String.join("\r\n", plainCodes) + "\r\n";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] csvContent(InviteCodeBatch batch, List<String> plainCodes) {
        StringBuilder content = new StringBuilder("\uFEFF");
        content.append("code,role,membership_days,expires_at,invite_expires_at,batch_no,note\r\n");
        for (String code : plainCodes) {
            content.append(csvCell(code)).append(',')
                    .append(csvCell(batch.getTargetRole())).append(',')
                    .append(csvCell(String.valueOf(normalizeMembershipDays(batch.getMembershipDays())))).append(',')
                    .append(csvCell(batch.getExpiresAt().toString())).append(',')
                    .append(csvCell(batch.getExpiresAt().toString())).append(',')
                    .append(csvCell(batch.getBatchNo())).append(',')
                    .append(csvCell(batch.getNote())).append("\r\n");
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String generateDisplayCode() {
        StringBuilder raw = new StringBuilder(RANDOM_CODE_LENGTH);
        for (int i = 0; i < RANDOM_CODE_LENGTH; i++) {
            raw.append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]);
        }
        return "CM-" + raw.substring(0, 5)
                + "-" + raw.substring(5, 10)
                + "-" + raw.substring(10, 15)
                + "-" + raw.substring(15, 20)
                + "-" + raw.substring(20);
    }

    private String newBatchNo(Instant now) {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]);
        }
        return "CM-" + BATCH_TIME.format(now) + "-" + suffix;
    }

    private String canonicalCode(String plainCode) {
        if (plainCode == null || plainCode.length() > 96) throw unavailableCode();
        String canonical = plainCode
                .toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "");
        if (!canonical.matches("CM[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{26}")) {
            throw unavailableCode();
        }
        return canonical;
    }

    private String hashCanonicalCode(String canonicalCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append("%02x".formatted(value & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("邀请码校验失败", e);
        }
    }

    private String normalizeTargetRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!"VIP".equals(value) && !"SVIP".equals(value)) {
            throw new IllegalArgumentException("邀请码只能升级为 VIP 或 SVIP");
        }
        return value;
    }

    private String normalizeFormat(String format) {
        String value = format == null ? "txt" : format.trim().toLowerCase(Locale.ROOT);
        if (!"txt".equals(value) && !"csv".equals(value)) {
            throw new IllegalArgumentException("导出格式只能是 TXT 或 CSV");
        }
        return value;
    }

    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) return "";
        String value = note.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private int membershipRank(String role) {
        if (role == null) return 0;
        return switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "VIP" -> 1;
            case "SVIP" -> 2;
            case "ADMIN" -> 3;
            default -> 0;
        };
    }

    private int normalizeMembershipDays(Integer membershipDays) {
        int value = membershipDays == null ? 30 : membershipDays;
        if (value < 1 || value > 365) {
            throw new IllegalArgumentException("会员期限应为 1-365 天");
        }
        return value;
    }

    private IllegalArgumentException unavailableCode() {
        return new IllegalArgumentException("邀请码无效或不可使用");
    }

    public record InviteBatchExport(
            String filename,
            String contentType,
            byte[] content,
            String batchNo,
            int count
    ) {}

    private record DailyWindow(String date, Instant start, Instant end) {}
}
