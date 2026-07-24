package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AiDailyUsage;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AiDailyUsageRepository;
import com.cloudmind.demo.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MembershipEntitlementServiceTest {
    private AppUserRepository userRepository;
    private AiDailyUsageRepository usageRepository;
    private MembershipEntitlementService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(AppUserRepository.class);
        usageRepository = mock(AiDailyUsageRepository.class);
        service = new MembershipEntitlementService(userRepository, usageRepository);
        ReflectionTestUtils.setField(service, "userMaxFileBytes", 20L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "vipMaxFileBytes", 50L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "userDailyAi", 10);
    }

    @Test
    void fileLimitIsEnforcedByMembershipRole() {
        AppUser normal = user("USER");
        AppUser vip = user("VIP");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.assertFileUploadAllowed(normal, 21L * 1024 * 1024)
        );
        assertDoesNotThrow(
                () -> service.assertFileUploadAllowed(vip, 21L * 1024 * 1024)
        );
    }

    @Test
    void dailyAiLimitCannotBeBypassedByCallingTheServiceAgain() {
        AppUser user = user("USER");
        AiDailyUsage usage = new AiDailyUsage();
        usage.setUser(user);
        usage.setUsageDate(LocalDate.now());
        usage.setUsedCount(10);
        when(userRepository.findForUpdateById(2L)).thenReturn(Optional.of(user));
        when(usageRepository.findByUser_IdAndUsageDate(any(), any()))
                .thenReturn(Optional.of(usage));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.consumeAiRequest(user)
        );
        Map<String, Object> summary = service.summary(user);
        assertEquals(0, summary.get("dailyAiRemaining"));
        assertEquals(10, summary.get("dailyAiUsed"));
    }

    @Test
    void expiredTemporaryMembershipImmediatelyUsesFallbackEntitlements() {
        AppUser expiredVip = user("VIP");
        expiredVip.setQuotaBytes(50L * 1024 * 1024 * 1024);
        expiredVip.setMembershipExpiresAt(Instant.now().minusSeconds(1));
        expiredVip.setMembershipFallbackRole("USER");
        expiredVip.setMembershipFallbackQuotaBytes(10L * 1024 * 1024 * 1024);

        Map<String, Object> summary = service.summary(expiredVip);

        assertEquals("USER", summary.get("role"));
        assertEquals("STANDARD", summary.get("membershipStatus"));
        assertEquals(20L * 1024 * 1024, summary.get("maxFileBytes"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.assertFileUploadAllowed(expiredVip, 21L * 1024 * 1024)
        );
    }

    private AppUser user(String role) {
        AppUser user = new AppUser();
        user.setId(2L);
        user.setUsername("student");
        user.setRole(role);
        user.setEnabled(true);
        user.setQuotaBytes(10L * 1024 * 1024 * 1024);
        return user;
    }
}
