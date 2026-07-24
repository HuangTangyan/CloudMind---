package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AiDailyUsage;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AiDailyUsageRepository;
import com.cloudmind.demo.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class MembershipEntitlementService {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AppUserRepository userRepository;
    private final AiDailyUsageRepository usageRepository;

    @Value("${cloudmind.demo.default-quota-bytes:10737418240}")
    private long userStorageBytes = 10L * 1024 * 1024 * 1024;

    @Value("${cloudmind.membership.vip-quota-bytes:53687091200}")
    private long vipStorageBytes = 50L * 1024 * 1024 * 1024;

    @Value("${cloudmind.membership.svip-quota-bytes:214748364800}")
    private long svipStorageBytes = 200L * 1024 * 1024 * 1024;

    @Value("${cloudmind.membership.user-max-file-bytes:20971520}")
    private long userMaxFileBytes = 20L * 1024 * 1024;

    @Value("${cloudmind.membership.vip-max-file-bytes:52428800}")
    private long vipMaxFileBytes = 50L * 1024 * 1024;

    @Value("${cloudmind.membership.svip-max-file-bytes:104857600}")
    private long svipMaxFileBytes = 100L * 1024 * 1024;

    @Value("${cloudmind.membership.user-daily-ai:10}")
    private int userDailyAi = 10;

    @Value("${cloudmind.membership.vip-daily-ai:50}")
    private int vipDailyAi = 50;

    @Value("${cloudmind.membership.svip-daily-ai:200}")
    private int svipDailyAi = 200;

    @Value("${cloudmind.membership.admin-daily-ai:1000}")
    private int adminDailyAi = 1000;

    public MembershipEntitlementService(
            AppUserRepository userRepository,
            AiDailyUsageRepository usageRepository
    ) {
        this.userRepository = userRepository;
        this.usageRepository = usageRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(AppUser user) {
        requireUsableUser(user);
        Instant now = Instant.now();
        String effectiveRole = effectiveRole(user, now);
        Policy policy = policyFor(effectiveRole);
        LocalDate date = LocalDate.now(CAMPUS_ZONE);
        int used = usageRepository.findByUser_IdAndUsageDate(user.getId(), date)
                .map(AiDailyUsage::getUsedCount)
                .orElse(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", effectiveRole);
        result.put("storageQuotaBytes", effectiveQuotaBytes(user, policy, now));
        result.put("maxFileBytes", policy.maxFileBytes());
        result.put("dailyAiLimit", policy.dailyAiLimit());
        result.put("dailyAiUsed", used);
        result.put("dailyAiRemaining", Math.max(0, policy.dailyAiLimit() - used));
        result.put("usageDate", date.toString());
        result.put("membershipExpiresAt", activeTemporaryMembership(user, now)
                ? user.getMembershipExpiresAt()
                : null);
        result.put("membershipStatus", activeTemporaryMembership(user, now)
                ? "TEMPORARY"
                : membershipRank(effectiveRole) > 0 ? "PERMANENT" : "STANDARD");
        return result;
    }

    public long storageQuotaBytes(String role) {
        return policyFor(role).storageQuotaBytes();
    }

    public long maxFileBytes(String role) {
        return policyFor(role).maxFileBytes();
    }

    public int dailyAiLimit(String role) {
        return policyFor(role).dailyAiLimit();
    }

    public void assertFileUploadAllowed(AppUser user, long sizeBytes) {
        requireUsableUser(user);
        long limit = maxFileBytes(effectiveRole(user, Instant.now()));
        if (sizeBytes < 0 || sizeBytes > limit) {
            throw new IllegalArgumentException(
                    "当前会员等级单文件上限为 " + humanSize(limit) + "，请压缩文件或升级会员"
            );
        }
    }

    @Transactional
    public Map<String, Object> consumeAiRequest(AppUser sessionUser) {
        requireUsableUser(sessionUser);
        AppUser user = userRepository.findForUpdateById(sessionUser.getId())
                .orElseThrow(() -> new SecurityException("账号不存在或已失效"));
        requireUsableUser(user);
        Policy policy = policyFor(effectiveRole(user, Instant.now()));
        LocalDate date = LocalDate.now(CAMPUS_ZONE);
        AiDailyUsage usage = usageRepository.findByUser_IdAndUsageDate(user.getId(), date)
                .orElseGet(() -> {
                    AiDailyUsage created = new AiDailyUsage();
                    created.setUser(user);
                    created.setUsageDate(date);
                    created.setUsedCount(0);
                    return created;
                });
        int used = usage.getUsedCount() == null ? 0 : Math.max(0, usage.getUsedCount());
        if (used >= policy.dailyAiLimit()) {
            throw new IllegalArgumentException(
                    "今日 AI 使用次数已达到 " + policy.dailyAiLimit() + " 次，请明天再试或升级会员"
            );
        }
        usage.setUsedCount(used + 1);
        usageRepository.save(usage);
        return Map.of(
                "limit", policy.dailyAiLimit(),
                "used", used + 1,
                "remaining", Math.max(0, policy.dailyAiLimit() - used - 1),
                "usageDate", date.toString()
        );
    }

    private Policy policyFor(String role) {
        return switch (normalizeRole(role)) {
            case "VIP" -> new Policy(
                    positive(vipStorageBytes, userStorageBytes),
                    positive(vipMaxFileBytes, userMaxFileBytes),
                    positive(vipDailyAi, userDailyAi)
            );
            case "SVIP" -> new Policy(
                    positive(svipStorageBytes, vipStorageBytes),
                    positive(svipMaxFileBytes, vipMaxFileBytes),
                    positive(svipDailyAi, vipDailyAi)
            );
            case "ADMIN" -> new Policy(
                    positive(svipStorageBytes, userStorageBytes),
                    positive(svipMaxFileBytes, userMaxFileBytes),
                    positive(adminDailyAi, svipDailyAi)
            );
            default -> new Policy(
                    Math.max(1L, userStorageBytes),
                    Math.max(1L, userMaxFileBytes),
                    Math.max(1, userDailyAi)
            );
        };
    }

    private String normalizeRole(String role) {
        return role == null ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }

    private String effectiveRole(AppUser user, Instant now) {
        if (user == null || !expiredTemporaryMembership(user, now)) {
            return normalizeRole(user == null ? null : user.getRole());
        }
        String fallbackRole = normalizeRole(user.getMembershipFallbackRole());
        return "ADMIN".equals(fallbackRole) ? "USER" : fallbackRole;
    }

    private long effectiveQuotaBytes(AppUser user, Policy policy, Instant now) {
        Long storedQuota = expiredTemporaryMembership(user, now)
                ? user.getMembershipFallbackQuotaBytes()
                : user.getQuotaBytes();
        return Math.max(storedQuota == null ? 0L : storedQuota, policy.storageQuotaBytes());
    }

    private boolean activeTemporaryMembership(AppUser user, Instant now) {
        return user != null
                && user.getMembershipExpiresAt() != null
                && user.getMembershipExpiresAt().isAfter(now);
    }

    private boolean expiredTemporaryMembership(AppUser user, Instant now) {
        return user != null
                && user.getMembershipExpiresAt() != null
                && !user.getMembershipExpiresAt().isAfter(now);
    }

    private int membershipRank(String role) {
        return switch (normalizeRole(role)) {
            case "VIP" -> 1;
            case "SVIP" -> 2;
            case "ADMIN" -> 3;
            default -> 0;
        };
    }

    private void requireUsableUser(AppUser user) {
        if (user == null || user.getId() == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new SecurityException("账号不存在或已被禁用");
        }
    }

    private long positive(long value, long fallback) {
        return value > 0 ? value : Math.max(1L, fallback);
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : Math.max(1, fallback);
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return (bytes / (1024L * 1024 * 1024)) + " GB";
        if (bytes >= 1024L * 1024) return (bytes / (1024L * 1024)) + " MB";
        return bytes + " B";
    }

    private record Policy(long storageQuotaBytes, long maxFileBytes, int dailyAiLimit) {}
}
