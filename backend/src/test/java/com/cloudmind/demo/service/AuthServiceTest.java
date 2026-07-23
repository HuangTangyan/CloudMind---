package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private AppUserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        authService = new AuthService(userRepository, passwordEncoder);
        ReflectionTestUtils.setField(authService, "defaultQuotaBytes", 10L * 1024 * 1024 * 1024);
        when(userRepository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));

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
