package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AuthToken;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.AuthTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private AuthTokenRepository authTokenRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;
    private Map<String, AuthToken> tokenDatabase;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        tokenDatabase = new ConcurrentHashMap<>();
        authService = new AuthService(userRepository, authTokenRepository, passwordEncoder);
        ReflectionTestUtils.setField(authService, "defaultQuotaBytes", 10L * 1024 * 1024 * 1024);
        lenient().when(userRepository.save(any(AppUser.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(authTokenRepository.save(any(AuthToken.class))).thenAnswer(invocation -> {
            AuthToken token = invocation.getArgument(0);
            tokenDatabase.put(token.getTokenType() + ":" + token.getTokenHash(), token);
            return token;
        });
        lenient().when(authTokenRepository.findByTokenHashAndTokenType(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(tokenDatabase.get(
                        invocation.getArgument(1) + ":" + invocation.getArgument(0)
                )));
        lenient().when(authTokenRepository.revokeUserTokens(anyLong(), any(Instant.class)))
                .thenAnswer(invocation -> revokeTokensByUser(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
        lenient().when(authTokenRepository.revokeFamily(anyString(), any(Instant.class)))
                .thenAnswer(invocation -> revokeTokensByFamily(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
        lenient().when(authTokenRepository.revokeFamilyForUser(
                        anyLong(),
                        anyString(),
                        any(Instant.class)
                ))
                .thenAnswer(invocation -> revokeTokensByUserAndFamily(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));
        lenient().when(authTokenRepository.findByUser_IdOrderByCreatedAtDesc(anyLong()))
                .thenAnswer(invocation -> tokenDatabase.values().stream()
                        .filter(token -> token.getUser().getId().equals(invocation.getArgument(0)))
                        .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                        .toList());
    }

    @Test
    void newPasswordsUseBcrypt() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        AppUser user = authService.register("alice", "safe-password");

        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches("safe-password", user.getPasswordHash()));
        assertEquals("", user.getSalt());
        assertNotNull(user.getPasswordChangedAt());
        assertNotNull(user.getUsernameChangedAt());
    }

    @Test
    void legacySha256PasswordIsMigratedAfterSuccessfulLogin() {
        AppUser user = user(1L, "legacy", "USER");
        user.setSalt("legacy-salt");
        user.setPasswordHash(legacyHash("old-password", user.getSalt()));
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("legacy")).thenReturn(Optional.of(user));

        authService.login("legacy", "old-password");

        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches("old-password", user.getPasswordHash()));
        verify(userRepository).save(user);
    }

    @Test
    void bootstrapAdminRequiresFirstLoginPasswordChange() {
        when(userRepository.findByUsername("secure_admin")).thenReturn(Optional.empty());

        authService.ensureAdmin("secure_admin", "A-strong-bootstrap-password");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(captor.capture());
        AppUser admin = captor.getValue();
        assertEquals("ADMIN", admin.getRole());
        assertTrue(passwordEncoder.matches("A-strong-bootstrap-password", admin.getPasswordHash()));
        assertNull(admin.getPasswordChangedAt());
        assertNotNull(admin.getUsernameChangedAt());
    }

    @Test
    void passwordChangeRotatesTokenAndUnlocksAccount() {
        AppUser admin = user(7L, "secure_admin", "ADMIN");
        admin.setPasswordHash(passwordEncoder.encode("A-strong-bootstrap-password"));
        admin.setSalt("");
        admin.setPasswordChangedAt(null);
        when(userRepository.findByUsername("secure_admin")).thenReturn(Optional.of(admin));

        Map<String, Object> login = authService.login("secure_admin", "A-strong-bootstrap-password");
        String oldToken = String.valueOf(login.get("token"));
        assertThrows(SecurityException.class, () -> authService.requireUser(oldToken));

        Map<String, Object> changed = authService.changePassword(
                oldToken,
                "A-strong-bootstrap-password",
                "A-new-secure-password"
        );
        String newToken = String.valueOf(changed.get("token"));

        assertNotEquals(oldToken, newToken);
        assertThrows(SecurityException.class, () -> authService.requireSessionUser(oldToken));
        assertEquals(admin, authService.requireUser(newToken));
        assertNotNull(admin.getPasswordChangedAt());
        assertTrue(passwordEncoder.matches("A-new-secure-password", admin.getPasswordHash()));
    }

    @Test
    void adminCreatedUserMustReplaceTemporaryUsernameAndPasswordTogether() {
        AppUser user = user(21L, "campus_temp_001", "USER");
        user.setPasswordHash(passwordEncoder.encode("CM-initial-password"));
        user.setSalt("");
        user.setUsernameChangedAt(null);
        user.setPasswordChangedAt(null);
        when(userRepository.findByUsername("campus_temp_001")).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("student_001")).thenReturn(false);

        Map<String, Object> login = authService.login(
                "campus_temp_001",
                "CM-initial-password"
        );
        String oldToken = String.valueOf(login.get("accessToken"));
        assertThrows(SecurityException.class, () -> authService.requireUser(oldToken));

        Map<String, Object> completed = authService.completeFirstLogin(
                oldToken,
                "CM-initial-password",
                "student_001",
                "student-new-password"
        );
        String newToken = String.valueOf(completed.get("accessToken"));

        assertEquals("student_001", user.getUsername());
        assertNotNull(user.getUsernameChangedAt());
        assertNotNull(user.getPasswordChangedAt());
        assertTrue(passwordEncoder.matches("student-new-password", user.getPasswordHash()));
        assertThrows(SecurityException.class, () -> authService.requireSessionUser(oldToken));
        assertEquals(user, authService.requireUser(newToken));
    }

    @Test
    void generatedCampusCredentialUsesUniqueStrongTemporaryPassword() {
        when(userRepository.existsByUsername("campus_temp_002")).thenReturn(false);

        AuthService.CreatedUserCredential created =
                authService.createTemporaryUserByAdmin(
                        "campus_temp_002",
                        "USER",
                        5L * 1024 * 1024 * 1024
                );

        assertEquals("campus_temp_002", created.user().getUsername());
        assertEquals("USER", created.user().getRole());
        assertTrue(created.temporaryPassword().startsWith("CM-"));
        assertTrue(created.temporaryPassword().length() >= 18);
        assertTrue(passwordEncoder.matches(
                created.temporaryPassword(),
                created.user().getPasswordHash()
        ));
        assertNull(created.user().getUsernameChangedAt());
        assertNull(created.user().getPasswordChangedAt());
    }

    @Test
    void refreshTokenIsSingleUseAndStoredOnlyAsHash() {
        AppUser user = user(3L, "alice", "USER");
        user.setPasswordHash(passwordEncoder.encode("safe-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        Map<String, Object> login = authService.login("alice", "safe-password");
        String accessToken = String.valueOf(login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));

        assertEquals(43, accessToken.length());
        assertEquals(64, refreshToken.length());
        assertTrue(tokenDatabase.values().stream()
                .allMatch(stored -> stored.getTokenHash().length() == 64));
        assertTrue(tokenDatabase.values().stream()
                .noneMatch(stored -> stored.getTokenHash().equals(accessToken)
                        || stored.getTokenHash().equals(refreshToken)));

        Map<String, Object> refreshed = authService.refreshSession(refreshToken);
        assertNotEquals(accessToken, refreshed.get("accessToken"));
        assertThrows(SecurityException.class, () -> authService.refreshSession(refreshToken));
    }

    @Test
    void logoutRevokesServerSideSession() {
        AppUser user = user(4L, "bob", "USER");
        user.setPasswordHash(passwordEncoder.encode("safe-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

        Map<String, Object> login = authService.login("bob", "safe-password");
        String accessToken = String.valueOf(login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));
        assertEquals(user, authService.requireSessionUser(accessToken));

        authService.logout(accessToken, refreshToken);

        assertThrows(SecurityException.class, () -> authService.requireSessionUser(accessToken));
        assertThrows(SecurityException.class, () -> authService.refreshSession(refreshToken));
    }

    @Test
    void roleChangeRevokesExistingSessionsImmediately() {
        AppUser user = user(10L, "operator", "ADMIN");
        user.setPasswordHash(passwordEncoder.encode("safe-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("operator")).thenReturn(Optional.of(user));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        Map<String, Object> login = authService.login("operator", "safe-password");
        String accessToken = String.valueOf(login.get("accessToken"));
        assertEquals(user, authService.requireSessionUser(accessToken));

        authService.updateUserByAdmin(10L, "USER", null);

        assertEquals("USER", user.getRole());
        assertThrows(SecurityException.class, () -> authService.requireSessionUser(accessToken));
        verify(authTokenRepository).revokeUserTokens(eq(10L), any(Instant.class));
    }

    @Test
    void expiredTemporaryMembershipFallsBackDuringLogin() {
        AppUser user = user(14L, "expired_member", "SVIP");
        user.setPasswordHash(passwordEncoder.encode("safe-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        user.setQuotaBytes(200L * 1024 * 1024 * 1024);
        user.setMembershipExpiresAt(Instant.now().minusSeconds(1));
        user.setMembershipFallbackRole("VIP");
        user.setMembershipFallbackQuotaBytes(50L * 1024 * 1024 * 1024);
        when(userRepository.findByUsername("expired_member")).thenReturn(Optional.of(user));

        Map<String, Object> login = authService.login("expired_member", "safe-password");

        assertEquals("VIP", user.getRole());
        assertEquals(50L * 1024 * 1024 * 1024, user.getQuotaBytes());
        assertNull(user.getMembershipExpiresAt());
        assertNull(user.getMembershipFallbackRole());
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionUser = (Map<String, Object>) login.get("user");
        assertEquals("VIP", sessionUser.get("role"));
    }

    @Test
    void userCanListAndRevokeOwnDeviceSession() {
        AppUser user = user(11L, "device_user", "USER");
        user.setPasswordHash(passwordEncoder.encode("safe-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("device_user")).thenReturn(Optional.of(user));

        Map<String, Object> login = authService.login(
                "device_user",
                "safe-password",
                "127.0.0.1",
                "CloudMind Test Browser"
        );
        String accessToken = String.valueOf(login.get("accessToken"));

        List<Map<String, Object>> sessions = authService.sessions(accessToken);
        assertEquals(1, sessions.size());
        assertEquals(true, sessions.get(0).get("current"));
        assertEquals("127.0.0.1", sessions.get(0).get("clientIp"));
        assertEquals("CloudMind Test Browser", sessions.get(0).get("userAgent"));

        String familyId = String.valueOf(sessions.get(0).get("id"));
        authService.revokeSession(accessToken, familyId);

        assertThrows(SecurityException.class, () -> authService.requireSessionUser(accessToken));
        verify(authTokenRepository).revokeFamilyForUser(
                eq(11L),
                eq(familyId),
                any(Instant.class)
        );
    }

    @Test
    void userCannotRevokeAnotherUsersDeviceSession() {
        AppUser alice = user(12L, "alice_device", "USER");
        alice.setPasswordHash(passwordEncoder.encode("safe-password"));
        alice.setSalt("");
        alice.setPasswordChangedAt(Instant.now());
        AppUser bob = user(13L, "bob_device", "USER");
        bob.setPasswordHash(passwordEncoder.encode("safe-password"));
        bob.setSalt("");
        bob.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("alice_device")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("bob_device")).thenReturn(Optional.of(bob));

        String aliceAccess = String.valueOf(
                authService.login("alice_device", "safe-password").get("accessToken")
        );
        String bobAccess = String.valueOf(
                authService.login("bob_device", "safe-password").get("accessToken")
        );
        String bobFamilyId = String.valueOf(authService.sessions(bobAccess).get(0).get("id"));

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.revokeSession(aliceAccess, bobFamilyId)
        );
        assertEquals(bob, authService.requireSessionUser(bobAccess));
        verify(authTokenRepository).revokeFamilyForUser(
                eq(12L),
                eq(bobFamilyId),
                any(Instant.class)
        );
    }

    @Test
    void locksAccountAfterRepeatedPasswordFailures() {
        AppUser user = user(8L, "locked_user", "USER");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setSalt("");
        user.setPasswordChangedAt(Instant.now());
        when(userRepository.findByUsername("locked_user")).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(authService, "maxLoginFailures", 3);
        ReflectionTestUtils.setField(authService, "loginLockDuration", Duration.ofMinutes(15));

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.login("locked_user", "wrong-password")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> authService.login("locked_user", "wrong-password")
        );
        assertThrows(
                LoginLockedException.class,
                () -> authService.login("locked_user", "wrong-password")
        );

        assertEquals(3, user.getFailedLoginAttempts());
        assertNotNull(user.getLockedUntil());
        assertTrue(user.getLockedUntil().isAfter(Instant.now()));
        assertThrows(
                LoginLockedException.class,
                () -> authService.login("locked_user", "correct-password")
        );
    }

    private int revokeTokensByUser(Long userId, Instant revokedAt) {
        int count = 0;
        for (AuthToken token : tokenDatabase.values()) {
            if (token.getUser().getId().equals(userId) && token.getRevokedAt() == null) {
                token.setRevokedAt(revokedAt);
                count++;
            }
        }
        return count;
    }

    private int revokeTokensByFamily(String familyId, Instant revokedAt) {
        int count = 0;
        for (AuthToken token : tokenDatabase.values()) {
            if (token.getFamilyId().equals(familyId) && token.getRevokedAt() == null) {
                token.setRevokedAt(revokedAt);
                count++;
            }
        }
        return count;
    }

    private int revokeTokensByUserAndFamily(Long userId, String familyId, Instant revokedAt) {
        int count = 0;
        for (AuthToken token : tokenDatabase.values()) {
            if (token.getUser().getId().equals(userId)
                    && token.getFamilyId().equals(familyId)
                    && token.getRevokedAt() == null) {
                token.setRevokedAt(revokedAt);
                count++;
            }
        }
        return count;
    }

    private AppUser user(Long id, String username, String role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(true);
        user.setQuotaBytes(10L * 1024 * 1024 * 1024);
        user.setUsernameChangedAt(Instant.now());
        return user;
    }

    private String legacyHash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
