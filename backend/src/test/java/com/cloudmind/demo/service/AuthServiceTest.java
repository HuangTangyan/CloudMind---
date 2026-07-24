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
import java.time.Instant;
import java.util.Base64;
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
    }

    @Test
    void newPasswordsUseBcrypt() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);

        AppUser user = authService.register("alice", "safe-password");

        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches("safe-password", user.getPasswordHash()));
        assertEquals("", user.getSalt());
        assertNotNull(user.getPasswordChangedAt());
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

    private AppUser user(Long id, String username, String role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(true);
        user.setQuotaBytes(10L * 1024 * 1024 * 1024);
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
