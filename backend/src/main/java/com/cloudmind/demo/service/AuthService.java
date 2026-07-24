package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AuthToken;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.AuthTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final AppUserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${cloudmind.demo.default-quota-bytes:10737418240}")
    private long defaultQuotaBytes;

    @Value("${cloudmind.auth.access-token-ttl:PT30M}")
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    @Value("${cloudmind.auth.refresh-token-ttl:P7D}")
    private Duration refreshTokenTtl = Duration.ofDays(7);

    @Value("${cloudmind.auth.max-login-failures:5}")
    private int maxLoginFailures = 5;

    @Value("${cloudmind.auth.login-lock-duration:PT15M}")
    private Duration loginLockDuration = Duration.ofMinutes(15);

    public AuthService(
            AppUserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String username, String password) {
        username = normalizeUsername(username);
        validatePassword(password);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        setPassword(user, password, true);
        user.setRole("USER");
        user.setQuotaBytes(defaultQuotaBytes);
        return userRepository.save(user);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, LoginLockedException.class})
    public Map<String, Object> login(String username, String password) {
        username = normalizeUsername(username);
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw locked(user.getLockedUntil(), now);
        }
        if (user.getLockedUntil() != null) {
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!passwordMatches(user, password)) {
            int failedAttempts = safeFailedAttempts(user) + 1;
            user.setFailedLoginAttempts(failedAttempts);
            if (failedAttempts >= Math.max(1, maxLoginFailures)) {
                Instant lockedUntil = now.plus(loginLockDuration);
                user.setLockedUntil(lockedUntil);
                userRepository.save(user);
                throw locked(lockedUntil, now);
            }
            userRepository.save(user);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (isLegacyHash(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setSalt("");
        }
        userRepository.save(user);
        return issueSession(user, UUID.randomUUID().toString());
    }

    public AppUser requireUser(String token) {
        AppUser user = requireSessionUser(token);
        if (user.getPasswordChangedAt() == null) {
            throw new SecurityException("首次登录必须先修改密码");
        }
        return user;
    }

    @Transactional(readOnly = true)
    public AppUser requireSessionUser(String token) {
        if (token == null || token.isBlank()) {
            throw new SecurityException("请先登录");
        }
        AuthToken storedToken = authTokenRepository
                .findByTokenHashAndTokenType(hashToken(token), AuthToken.ACCESS)
                .orElseThrow(() -> new SecurityException("登录状态已失效，请重新登录"));
        validateActiveToken(storedToken, "登录状态已过期，请重新登录");
        AppUser user = storedToken.getUser();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new SecurityException("账号已被禁用");
        }
        return user;
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Map<String, Object> refreshSession(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new SecurityException("刷新令牌不能为空");
        }
        AuthToken storedToken = authTokenRepository
                .findByTokenHashAndTokenType(hashToken(refreshToken), AuthToken.REFRESH)
                .orElseThrow(() -> new SecurityException("刷新令牌无效，请重新登录"));
        if (storedToken.getRevokedAt() != null) {
            authTokenRepository.revokeFamily(storedToken.getFamilyId(), Instant.now());
            throw new SecurityException("刷新令牌已失效，请重新登录");
        }
        validateActiveToken(storedToken, "登录状态已过期，请重新登录");
        AppUser user = storedToken.getUser();
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            authTokenRepository.revokeFamily(storedToken.getFamilyId(), Instant.now());
            throw new SecurityException("账号已被禁用");
        }
        authTokenRepository.revokeFamily(storedToken.getFamilyId(), Instant.now());
        return issueSession(user, storedToken.getFamilyId());
    }

    @Transactional
    public void logout(String accessToken, String refreshToken) {
        Optional<AuthToken> stored = findAnyToken(accessToken, AuthToken.ACCESS)
                .or(() -> findAnyToken(refreshToken, AuthToken.REFRESH));
        stored.ifPresent(token -> authTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    public AppUser requireAdmin(String token) {
        AppUser user = requireUser(token);
        if (!isAdmin(user)) {
            throw new SecurityException("需要管理员权限");
        }
        return user;
    }

    public boolean isAdmin(AppUser user) {
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    public Map<String, Object> toUserMap(AppUser user) {
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "role", user.getRole(),
                "permissionLevel", user.getRole(),
                "quotaBytes", user.getQuotaBytes(),
                "enabled", user.getEnabled(),
                "mustChangePassword", user.getPasswordChangedAt() == null
        );
    }

    @Transactional
    public void ensureAdmin(String username, String password) {
        boolean usernameMissing = username == null || username.isBlank();
        boolean passwordMissing = password == null || password.isBlank();
        if (usernameMissing && passwordMissing) {
            return;
        }
        if (usernameMissing || passwordMissing) {
            throw new IllegalStateException("管理员引导账号必须同时配置用户名和密码");
        }
        validateBootstrapAdminPassword(password);
        String normalized = normalizeUsername(username);
        Optional<AppUser> old = userRepository.findByUsername(normalized);
        if (old.isPresent()) {
            if (!isAdmin(old.get())) {
                throw new IllegalStateException("管理员引导用户名已被普通账号占用");
            }
            return;
        }
        AppUser admin = new AppUser();
        admin.setUsername(normalized);
        setPassword(admin, password, false);
        admin.setRole("ADMIN");
        admin.setQuotaBytes(defaultQuotaBytes);
        userRepository.save(admin);
    }

    @Transactional
    public Map<String, Object> changePassword(String token, String currentPassword, String newPassword) {
        AppUser user = requireSessionUser(token);
        if (!passwordMatches(user, currentPassword)) {
            throw new IllegalArgumentException("当前密码错误");
        }
        if (isAdmin(user)) validateAdminPassword(newPassword);
        else validatePassword(newPassword);
        if (passwordMatches(user, newPassword)) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        setPassword(user, newPassword, true);
        userRepository.save(user);
        revokeTokens(user.getId());
        return issueSession(user, UUID.randomUUID().toString());
    }

    @Transactional
    public AppUser createUserByAdmin(String username, String password, String role, Long quotaBytes) {
        username = normalizeUsername(username);
        validatePassword(password);
        if (userRepository.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setRole(normalizeRole(role));
        if (isAdmin(user)) validateAdminPassword(password);
        setPassword(user, password, false);
        user.setQuotaBytes(normalizeQuota(quotaBytes));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Transactional
    public AppUser updateUserByAdmin(Long userId, String role, Long quotaBytes) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (role != null && !role.isBlank()) user.setRole(normalizeRole(role));
        if (quotaBytes != null) user.setQuotaBytes(normalizeQuota(quotaBytes));
        return userRepository.save(user);
    }

    @Transactional
    public AppUser setUserEnabledByAdmin(AppUser admin, Long userId, boolean enabled) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (admin.getId().equals(user.getId())) throw new IllegalArgumentException("不能封禁/解封当前登录的管理员账号");
        user.setEnabled(enabled);
        if (!enabled) revokeTokens(user.getId());
        return userRepository.save(user);
    }

    @Transactional
    public String resetPasswordByAdmin(AppUser admin, Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (admin.getId().equals(user.getId())) throw new IllegalArgumentException("为了避免误操作，管理员不能在这里重置自己的密码");
        String newPassword = randomPassword();
        setPassword(user, newPassword, false);
        userRepository.save(user);
        revokeTokens(user.getId());
        return newPassword;
    }

    private void setPassword(AppUser user, String password, boolean userSelectedPassword) {
        validatePassword(password);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setSalt("");
        user.setPasswordChangedAt(userSelectedPassword ? Instant.now() : null);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }

    private boolean passwordMatches(AppUser user, String password) {
        if (password == null || user.getPasswordHash() == null) return false;
        if (!isLegacyHash(user.getPasswordHash())) {
            return passwordEncoder.matches(password, user.getPasswordHash());
        }
        return legacyHash(password, user.getSalt()).equals(user.getPasswordHash());
    }

    private boolean isLegacyHash(String encoded) {
        return encoded != null && !encoded.startsWith("$2");
    }

    private Map<String, Object> issueSession(AppUser user, String familyId) {
        Instant now = Instant.now();
        String accessToken = randomToken(32);
        String refreshToken = randomToken(48);
        authTokenRepository.save(newToken(
                user,
                accessToken,
                AuthToken.ACCESS,
                familyId,
                now.plus(accessTokenTtl)
        ));
        authTokenRepository.save(newToken(
                user,
                refreshToken,
                AuthToken.REFRESH,
                familyId,
                now.plus(refreshTokenTtl)
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", accessToken);
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("expiresInSeconds", accessTokenTtl.toSeconds());
        result.put("refreshExpiresInSeconds", refreshTokenTtl.toSeconds());
        result.put("user", toUserMap(user));
        return result;
    }

    private void revokeTokens(Long userId) {
        authTokenRepository.revokeUserTokens(userId, Instant.now());
    }

    private int safeFailedAttempts(AppUser user) {
        return user.getFailedLoginAttempts() == null
                ? 0
                : Math.max(0, user.getFailedLoginAttempts());
    }

    private LoginLockedException locked(Instant lockedUntil, Instant now) {
        long retryAfterSeconds = Math.max(
                1L,
                Duration.between(now, lockedUntil).toSeconds()
        );
        return new LoginLockedException("登录失败次数过多，请稍后再试", retryAfterSeconds);
    }

    @Transactional
    public void deleteTokensForUser(Long userId) {
        authTokenRepository.deleteByUserId(userId);
    }

    @Scheduled(fixedDelayString = "${cloudmind.auth.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupTokens() {
        Instant now = Instant.now();
        authTokenRepository.deleteExpiredOrOldRevoked(now, now.minus(Duration.ofDays(1)));
    }

    private AuthToken newToken(
            AppUser user,
            String rawToken,
            String tokenType,
            String familyId,
            Instant expiresAt
    ) {
        AuthToken token = new AuthToken();
        token.setUser(user);
        token.setTokenHash(hashToken(rawToken));
        token.setTokenType(tokenType);
        token.setFamilyId(familyId);
        token.setExpiresAt(expiresAt);
        return token;
    }

    private Optional<AuthToken> findAnyToken(String rawToken, String tokenType) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return authTokenRepository.findByTokenHashAndTokenType(hashToken(rawToken), tokenType);
    }

    private void validateActiveToken(AuthToken token, String expiredMessage) {
        if (token.getRevokedAt() != null) {
            throw new SecurityException("登录状态已失效，请重新登录");
        }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(Instant.now())) {
            throw new SecurityException(expiredMessage);
        }
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        if (rawToken == null || rawToken.length() > 512) {
            throw new SecurityException("令牌格式无效");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append("%02x".formatted(value & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("令牌校验失败", e);
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) throw new IllegalArgumentException("用户名不能为空");
        String value = username.trim();
        if (value.length() < 3 || value.length() > 32) {
            throw new IllegalArgumentException("用户名长度应为 3-32 位");
        }
        if (!value.matches("[A-Za-z0-9_\\u4e00-\\u9fa5]+")) {
            throw new IllegalArgumentException("用户名只能包含中文、字母、数字和下划线");
        }
        return value;
    }

    private String normalizeRole(String role) {
        String value = role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
        return switch (value) {
            case "ADMIN", "USER", "VIP", "SVIP" -> value;
            default -> throw new IllegalArgumentException("权限等级只能是 USER、VIP、SVIP 或 ADMIN");
        };
    }

    private long normalizeQuota(Long quotaBytes) {
        if (quotaBytes == null) return defaultQuotaBytes;
        if (quotaBytes < 1024L * 1024L) throw new IllegalArgumentException("容量不能小于 1MB");
        return quotaBytes;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码至少 6 位，且 UTF-8 编码后不能超过 72 字节");
        }
    }

    private void validateBootstrapAdminPassword(String password) {
        if (password == null || password.length() < 14 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("管理员引导密码至少 14 位，且 UTF-8 编码后不能超过 72 字节");
        }
    }

    private void validateAdminPassword(String password) {
        if (password == null || password.length() < 14 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("管理员密码至少 14 位，且 UTF-8 编码后不能超过 72 字节");
        }
    }

    private String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder("CM-");
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String legacyHash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(((salt == null ? "" : salt) + ":" + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("密码校验失败", e);
        }
    }
}
