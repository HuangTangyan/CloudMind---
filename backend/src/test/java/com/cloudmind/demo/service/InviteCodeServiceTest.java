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
import com.cloudmind.demo.repository.AuthTokenRepository;
import com.cloudmind.demo.repository.AiDailyUsageRepository;
import com.cloudmind.demo.repository.InviteAuditLogRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteCodeServiceTest {
    private InviteCodeBatchRepository batchRepository;
    private InviteCodeRepository codeRepository;
    private StubAuthService authService;
    private AppUserRepository userRepository;
    private InviteAuditLogRepository auditRepository;
    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(InviteCodeBatchRepository.class);
        codeRepository = mock(InviteCodeRepository.class);
        userRepository = mock(AppUserRepository.class);
        auditRepository = mock(InviteAuditLogRepository.class);
        authService = new StubAuthService();
        MembershipEntitlementService membershipEntitlementService =
                new MembershipEntitlementService(
                        userRepository,
                        mock(AiDailyUsageRepository.class)
                );
        service = new InviteCodeService(
                batchRepository,
                codeRepository,
                authService,
                userRepository,
                membershipEntitlementService,
                new InviteAuditService(auditRepository)
        );
        ReflectionTestUtils.setField(service, "maxBatchSize", 500);
        ReflectionTestUtils.setField(service, "dailyGenerationLimit", 2000);
        when(batchRepository.save(any(InviteCodeBatch.class))).thenAnswer(invocation -> {
            InviteCodeBatch batch = invocation.getArgument(0);
            batch.setId(11L);
            return batch;
        });
        when(codeRepository.existsByCodeHash(anyString())).thenReturn(false);
        when(batchRepository.sumGeneratedByAdminBetween(any(), any(), any())).thenReturn(0L);
        when(userRepository.findForUpdateById(any())).thenAnswer(invocation ->
                Optional.of(admin()));
    }

    @Test
    void txtExportContainsOneFreshCodePerLineButPersistenceStoresOnlyHashes() {
        AppUser admin = admin();
        CreateInviteBatchRequest request = batchRequest("VIP", 3);

        InviteCodeService.InviteBatchExport export =
                service.createBatchExport(admin, request, "txt");

        List<String> lines = new String(export.content(), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();
        assertEquals(3, lines.size());
        assertEquals(3, lines.stream().distinct().count());
        assertTrue(lines.stream().allMatch(code ->
                code.matches("CM-[A-Z2-9]{5}-[A-Z2-9]{5}-[A-Z2-9]{5}-[A-Z2-9]{5}-[A-Z2-9]{6}")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InviteCode>> captor = ArgumentCaptor.forClass(List.class);
        verify(codeRepository).saveAll(captor.capture());
        List<InviteCode> storedCodes = captor.getValue();
        assertEquals(3, storedCodes.size());
        for (int i = 0; i < storedCodes.size(); i++) {
            assertEquals(64, storedCodes.get(i).getCodeHash().length());
            assertNotEquals(lines.get(i), storedCodes.get(i).getCodeHash());
            assertFalse(export.filename().contains(lines.get(i)));
        }
    }

    @Test
    void csvExportHasBomSafeHeaderAndRequiredColumns() {
        AppUser admin = admin();
        CreateInviteBatchRequest request = batchRequest("SVIP", 2);

        InviteCodeService.InviteBatchExport export =
                service.createBatchExport(admin, request, "csv");

        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        assertArrayEquals(bom, new byte[]{
                export.content()[0], export.content()[1], export.content()[2]
        });
        String csv = new String(export.content(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("code,role,expires_at,batch_no"));
        assertTrue(csv.contains("\"SVIP\""));
        assertTrue(csv.contains("\"CM-"));
        assertEquals("text/csv;charset=UTF-8", export.contentType());
    }

    @Test
    void redeemLocksCodeMarksItUsedAndReturnsRotatedSession() {
        String plainCode = "CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234";
        AppUser user = user("USER");
        InviteCode inviteCode = inviteCode("VIP", Instant.now().plusSeconds(3600));
        authService.requiredUser = user;
        when(codeRepository.findForUpdateByCodeHash(hash(plainCode)))
                .thenReturn(Optional.of(inviteCode));
        Map<String, Object> rotatedSession = Map.of("accessToken", "new-token");
        authService.rotatedSession = rotatedSession;

        Map<String, Object> result = service.redeem("old-token", plainCode);

        assertEquals("VIP", result.get("targetRole"));
        assertEquals(rotatedSession, result.get("session"));
        assertEquals(user, inviteCode.getRedeemedBy());
        assertNotNull(inviteCode.getRedeemedAt());
        assertTrue(authService.upgradeCalled);
        assertEquals("VIP", authService.lastTargetRole);
        assertEquals(50L * 1024 * 1024 * 1024, authService.lastTargetQuota);
        verify(codeRepository).save(inviteCode);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.redeem("old-token", plainCode)
        );
    }

    @Test
    void sameOrHigherMembershipDoesNotConsumeCode() {
        String plainCode = "CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234";
        AppUser user = user("SVIP");
        InviteCode inviteCode = inviteCode("VIP", Instant.now().plusSeconds(3600));
        authService.requiredUser = user;
        when(codeRepository.findForUpdateByCodeHash(hash(plainCode)))
                .thenReturn(Optional.of(inviteCode));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.redeem("token", plainCode)
        );

        assertTrue(error.getMessage().contains("无需使用"));
        assertEquals(null, inviteCode.getRedeemedAt());
        assertFalse(authService.upgradeCalled);
    }

    @Test
    void expiredCodeCannotBeConsumed() {
        String plainCode = "CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234";
        AppUser user = user("USER");
        InviteCode inviteCode = inviteCode("VIP", Instant.now().minusSeconds(1));
        authService.requiredUser = user;
        when(codeRepository.findForUpdateByCodeHash(hash(plainCode)))
                .thenReturn(Optional.of(inviteCode));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.redeem("token", plainCode)
        );
        assertEquals(null, inviteCode.getRedeemedAt());
    }

    @Test
    void revokedCodeCannotBeConsumed() {
        String plainCode = "CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234";
        InviteCode inviteCode = inviteCode("VIP", Instant.now().plusSeconds(3600));
        inviteCode.setRevokedAt(Instant.now());
        authService.requiredUser = user("USER");
        when(codeRepository.findForUpdateByCodeHash(hash(plainCode)))
                .thenReturn(Optional.of(inviteCode));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.redeem("token", plainCode)
        );
        assertFalse(authService.upgradeCalled);
    }

    @Test
    void dailyGenerationLimitRejectsExcessBeforeSavingBatch() {
        ReflectionTestUtils.setField(service, "dailyGenerationLimit", 10);
        when(batchRepository.sumGeneratedByAdminBetween(any(), any(), any()))
                .thenReturn(9L);
        CreateInviteBatchRequest request = batchRequest("VIP", 2);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.createBatchExport(admin(), request, "txt")
        );

        assertTrue(error.getMessage().contains("安全上限"));
    }

    @Test
    void administratorCanRevokeOneUnusedCodeWithReauthentication() {
        String plainCode = "CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234";
        InviteCode inviteCode = inviteCode("VIP", Instant.now().plusSeconds(3600));
        when(codeRepository.findForUpdateByCodeHash(hash(plainCode)))
                .thenReturn(Optional.of(inviteCode));
        RevokeInviteCodeRequest request = new RevokeInviteCodeRequest();
        request.setCode(plainCode);
        request.setReason("疑似泄露");
        request.setCurrentPassword("admin-password");

        Map<String, Object> result = service.revokeCode(
                admin(), request, "127.0.0.1", "JUnit"
        );

        assertNotNull(inviteCode.getRevokedAt());
        assertEquals("疑似泄露", inviteCode.getRevokeReason());
        assertEquals(12, String.valueOf(result.get("codeFingerprint")).length());
        verify(codeRepository).save(inviteCode);
    }

    @Test
    void administratorCanRevokeAllUnusedCodesInABatch() {
        InviteCodeBatch batch = inviteCode("VIP", Instant.now().plusSeconds(3600)).getBatch();
        when(batchRepository.findForUpdateById(batch.getId())).thenReturn(Optional.of(batch));
        when(codeRepository.revokeUnusedByBatch(any(), any(), any(), anyString()))
                .thenReturn(12);
        RevokeInviteBatchRequest request = new RevokeInviteBatchRequest();
        request.setReason("活动结束");
        request.setCurrentPassword("admin-password");

        Map<String, Object> result = service.revokeBatch(
                admin(), batch.getId(), request, "127.0.0.1", "JUnit"
        );

        assertNotNull(batch.getRevokedAt());
        assertEquals(12, result.get("revokedCount"));
        assertEquals("活动结束", batch.getRevokeReason());
        verify(batchRepository).save(batch);
    }

    @Test
    void repositoryAcquiresPessimisticWriteLockForConcurrentRedemptions()
            throws Exception {
        Lock lock = InviteCodeRepository.class
                .getMethod("findForUpdateByCodeHash", String.class)
                .getAnnotation(Lock.class);
        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    private CreateInviteBatchRequest batchRequest(String role, int count) {
        CreateInviteBatchRequest request = new CreateInviteBatchRequest();
        request.setRole(role);
        request.setCount(count);
        request.setExpiresAt(Instant.now().plusSeconds(30L * 24 * 3600));
        request.setNote("校园内测");
        request.setCurrentPassword("admin-password");
        return request;
    }

    private InviteCode inviteCode(String targetRole, Instant expiresAt) {
        InviteCodeBatch batch = new InviteCodeBatch();
        batch.setId(15L);
        batch.setBatchNo("CM-20260724-000000-ABCDEF");
        batch.setTargetRole(targetRole);
        batch.setExpiresAt(expiresAt);
        InviteCode code = new InviteCode();
        code.setId(19L);
        code.setBatch(batch);
        code.setCodeHash(hash("CM-ABCDE-FGHJK-MNPQR-STUVW-XYZ234"));
        return code;
    }

    private AppUser admin() {
        AppUser admin = new AppUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        return admin;
    }

    private AppUser user(String role) {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setUsername("student");
        user.setRole(role);
        user.setEnabled(true);
        user.setQuotaBytes(10L * 1024 * 1024 * 1024);
        user.setPasswordChangedAt(Instant.now());
        return user;
    }

    private String hash(String displayCode) {
        try {
            String canonical = displayCode.replace("-", "").toUpperCase();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append("%02x".formatted(value & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static class StubAuthService extends AuthService {
        private AppUser requiredUser;
        private Map<String, Object> rotatedSession = Map.of("accessToken", "rotated-token");
        private boolean upgradeCalled;
        private String lastTargetRole;
        private long lastTargetQuota;

        StubAuthService() {
            super(
                    mock(AppUserRepository.class),
                    mock(AuthTokenRepository.class),
                    new BCryptPasswordEncoder(4)
            );
        }

        @Override
        public boolean isAdmin(AppUser user) {
            return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
        }

        @Override
        public void reauthenticateAdmin(AppUser admin, String currentPassword) {
            if (!"admin-password".equals(currentPassword)) {
                throw new SecurityException("管理员身份验证失败");
            }
        }

        @Override
        public AppUser requireUser(String token) {
            if (requiredUser == null) throw new SecurityException("请先登录");
            return requiredUser;
        }

        @Override
        public Map<String, Object> upgradeMembershipAndRotateSession(
                String accessToken,
                AppUser user,
                String targetRole,
                long targetQuotaBytes
        ) {
            upgradeCalled = true;
            lastTargetRole = targetRole;
            lastTargetQuota = targetQuotaBytes;
            return rotatedSession;
        }
    }
}
