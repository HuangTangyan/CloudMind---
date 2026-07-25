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
import java.util.List;
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
        user.setUsernameChangedAt(Instant.now());
        setPassword(user, password, true);
        user.setRole("USER");
        user.setQuotaBytes(defaultQuotaBytes);
        return userRepository.save(user);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, LoginLockedException.class})
    public Map<String, Object> login(String username, String password) {
        return login(username, password, null, null);
    }

    @Transactional(noRollbackFor = {IllegalArgumentException.class, LoginLockedException.class})
    public Map<String, Object> login(
            String username,
            String password,
            String clientIp,
            String userAgent
    ) {
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
        normalizeExpiredMembership(user, now);
        userRepository.save(user);
        return issueSession(user, UUID.randomUUID().toString(), clientIp, userAgent);
    }

    public AppUser requireUser(String token) {
        AppUser user = requireSessionUser(token);
        if (user.getPasswordChangedAt() == null || user.getUsernameChangedAt() == null) {
            throw new SecurityException("首次登录必须先修改用户名和初始密码");
        }
        return user;
    }

    @Transactional
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
        Instant usedAt = Instant.now();
        if (normalizeExpiredMembership(user, usedAt)) {
            userRepository.save(user);
        }
        if (storedToken.getId() != null) {
            authTokenRepository.touchLastUsed(
                    storedToken.getId(),
                    usedAt,
                    usedAt.minus(Duration.ofMinutes(5))
            );
        }
        storedToken.setLastUsedAt(usedAt);
        return user;
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Map<String, Object> refreshSession(String refreshToken) {
        return refreshSession(refreshToken, null, null);
    }

    @Transactional(noRollbackFor = SecurityException.class)
    public Map<String, Object> refreshSession(
            String refreshToken,
            String clientIp,
            String userAgent
    ) {
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
        Instant now = Instant.now();
        if (normalizeExpiredMembership(user, now)) {
            userRepository.save(user);
        }
        authTokenRepository.revokeFamily(storedToken.getFamilyId(), now);
        String resolvedIp = clientIp == null || clientIp.isBlank()
                ? storedToken.getClientIp()
                : clientIp;
        String resolvedUserAgent = userAgent == null || userAgent.isBlank()
                ? storedToken.getUserAgent()
                : userAgent;
        return issueSession(
                user,
                storedToken.getFamilyId(),
                resolvedIp,
                resolvedUserAgent
        );
    }

    @Transactional
    public void logout(String accessToken, String refreshToken) {
        Optional<AuthToken> stored = findAnyToken(accessToken, AuthToken.ACCESS)
                .or(() -> findAnyToken(refreshToken, AuthToken.REFRESH));
        stored.ifPresent(token -> authTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sessions(String accessToken) {
        AuthToken current = requireStoredAccessToken(accessToken);
        AppUser user = current.getUser();
        Instant now = Instant.now();
        LinkedHashMap<String, Map<String, Object>> families = new LinkedHashMap<>();
        mergeSessionToken(families, current, current.getFamilyId(), now);
        for (AuthToken token : authTokenRepository
                .findByUser_IdOrderByCreatedAtDesc(user.getId())) {
            mergeSessionToken(families, token, current.getFamilyId(), now);
        }
        return List.copyOf(families.values());
    }

    @Transactional
    public void revokeSession(String accessToken, String familyId) {
        AuthToken current = requireStoredAccessToken(accessToken);
        if (familyId == null || familyId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        int revoked = authTokenRepository.revokeFamilyForUser(
                current.getUser().getId(),
                familyId.trim(),
                Instant.now()
        );
        if (revoked == 0) {
            throw new IllegalArgumentException("会话不存在或已经下线");
        }
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

    public void reauthenticateAdmin(AppUser admin, String currentPassword) {
        if (!isAdmin(admin)
                || currentPassword == null
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordMatches(admin, currentPassword)) {
            throw new SecurityException("管理员身份验证失败");
        }
    }

    public Map<String, Object> toUserMap(AppUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("permissionLevel", user.getRole());
        result.put("quotaBytes", user.getQuotaBytes());
        result.put("enabled", user.getEnabled());
        result.put("mustChangePassword", user.getPasswordChangedAt() == null);
        result.put("mustChangeUsername", user.getUsernameChangedAt() == null);
        result.put(
                "mustCompleteFirstLogin",
                user.getPasswordChangedAt() == null || user.getUsernameChangedAt() == null
        );
        result.put("membershipExpiresAt", user.getMembershipExpiresAt());
        result.put("membershipFallbackRole", user.getMembershipFallbackRole());
        return result;
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
        admin.setUsernameChangedAt(Instant.now());
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
    public Map<String, Object> completeFirstLogin(
            String token,
            String currentPassword,
            String newUsername,
            String newPassword
    ) {
        AppUser user = requireSessionUser(token);
        boolean usernameChangeRequired = user.getUsernameChangedAt() == null;
        boolean passwordChangeRequired = user.getPasswordChangedAt() == null;
        if (!usernameChangeRequired && !passwordChangeRequired) {
            throw new IllegalArgumentException("首次登录设置已经完成");
        }
        if (!passwordMatches(user, currentPassword)) {
            throw new IllegalArgumentException("当前密码错误");
        }

        String normalizedUsername = null;
        if (usernameChangeRequired) {
            normalizedUsername = normalizeUsername(newUsername);
            if (normalizedUsername.equalsIgnoreCase(user.getUsername())) {
                throw new IllegalArgumentException("新用户名不能与临时用户名相同");
            }
            if (userRepository.existsByUsername(normalizedUsername)) {
                throw new IllegalArgumentException("用户名已存在");
            }
        }

        if (isAdmin(user)) validateAdminPassword(newPassword);
        else validatePassword(newPassword);
        if (passwordMatches(user, newPassword)) {
            throw new IllegalArgumentException("新密码不能与初始密码相同");
        }

        if (usernameChangeRequired) {
            user.setUsername(normalizedUsername);
        }
        user.setUsernameChangedAt(Instant.now());
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
        user.setUsernameChangedAt(null);
        user.setRole(normalizeRole(role));
        if (isAdmin(user)) validateAdminPassword(password);
        setPassword(user, password, false);
        user.setQuotaBytes(normalizeQuota(quotaBytes));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    @Transactional
    public CreatedUserCredential createTemporaryUserByAdmin(
            String username,
            String role,
            Long quotaBytes
    ) {
        String temporaryPassword = randomPassword();
        AppUser user = createUserByAdmin(username, temporaryPassword, role, quotaBytes);
        return new CreatedUserCredential(user, temporaryPassword);
    }

    public record CreatedUserCredential(AppUser user, String temporaryPassword) {}

    @Transactional
    public AppUser updateUserByAdmin(Long userId, String role, Long quotaBytes) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (role != null && !role.isBlank()) {
            String normalizedRole = normalizeRole(role);
            if (!normalizedRole.equalsIgnoreCase(user.getRole())) {
                user.setRole(normalizedRole);
                revokeTokens(user.getId());
            }
            clearTemporaryMembership(user);
        }
        if (quotaBytes != null) user.setQuotaBytes(normalizeQuota(quotaBytes));
        return userRepository.save(user);
    }

    @Transactional
    public Map<String, Object> upgradeMembershipAndRotateSession(
            String accessToken,
            AppUser user,
            String targetRole,
            long targetQuotaBytes
    ) {
        return upgradeMembershipAndRotateSession(
                accessToken,
                user,
                targetRole,
                targetQuotaBytes,
                30
        );
    }

    @Transactional
    public Map<String, Object> upgradeMembershipAndRotateSession(
            String accessToken,
            AppUser user,
            String targetRole,
            long targetQuotaBytes,
            int membershipDays
    ) {
        AuthToken currentToken = requireStoredAccessToken(accessToken);
        if (user == null
                || user.getId() == null
                || currentToken.getUser() == null
                || !user.getId().equals(currentToken.getUser().getId())) {
            throw new SecurityException("登录状态与兑换账号不匹配");
        }

        AppUser lockedUser = userRepository.findForUpdateById(user.getId())
                .orElseThrow(() -> new SecurityException("兑换账号不存在或已失效"));
        if (!Boolean.TRUE.equals(lockedUser.getEnabled())) {
            throw new SecurityException("兑换账号不存在或已失效");
        }
        Instant now = Instant.now();
        normalizeExpiredMembership(lockedUser, now);

        String normalizedRole = normalizeRole(targetRole);
        if (!"VIP".equals(normalizedRole) && !"SVIP".equals(normalizedRole)) {
            throw new IllegalArgumentException("邀请码只能升级为 VIP 或 SVIP");
        }
        if (membershipDays < 1 || membershipDays > 365) {
            throw new IllegalArgumentException("会员期限应为 1-365 天");
        }

        int currentRank = membershipRank(lockedUser.getRole());
        int targetRank = membershipRank(normalizedRole);
        boolean temporaryMembership = lockedUser.getMembershipExpiresAt() != null
                && lockedUser.getMembershipExpiresAt().isAfter(now);
        if (currentRank > targetRank || currentRank == targetRank && !temporaryMembership) {
            throw new IllegalArgumentException("当前会员等级无需使用该邀请码");
        }

        String clientIp = currentToken.getClientIp();
        String userAgent = currentToken.getUserAgent();
        if (!temporaryMembership) {
            lockedUser.setMembershipFallbackRole(normalizeRole(lockedUser.getRole()));
            lockedUser.setMembershipFallbackQuotaBytes(lockedUser.getQuotaBytes());
        }
        Instant extensionBase = temporaryMembership
                ? lockedUser.getMembershipExpiresAt()
                : now;
        lockedUser.setRole(normalizedRole);
        lockedUser.setMembershipExpiresAt(extensionBase.plus(Duration.ofDays(membershipDays)));
        lockedUser.setQuotaBytes(Math.max(
                lockedUser.getQuotaBytes() == null ? 0L : lockedUser.getQuotaBytes(),
                normalizeQuota(targetQuotaBytes)
        ));
        userRepository.save(lockedUser);

        revokeTokens(lockedUser.getId());
        return issueSession(
                lockedUser,
                UUID.randomUUID().toString(),
                clientIp,
                userAgent
        );
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
        return issueSession(user, familyId, null, null);
    }

    private Map<String, Object> issueSession(
            AppUser user,
            String familyId,
            String clientIp,
            String userAgent
    ) {
        Instant now = Instant.now();
        String accessToken = randomToken(32);
        String refreshToken = randomToken(48);
        authTokenRepository.save(newToken(
                user,
                accessToken,
                AuthToken.ACCESS,
                familyId,
                now.plus(accessTokenTtl),
                clientIp,
                userAgent,
                now
        ));
        authTokenRepository.save(newToken(
                user,
                refreshToken,
                AuthToken.REFRESH,
                familyId,
                now.plus(refreshTokenTtl),
                clientIp,
                userAgent,
                now
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

    @Scheduled(fixedDelayString = "${cloudmind.membership.expiry-cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredMemberships() {
        Instant now = Instant.now();
        List<AppUser> expiredUsers =
                userRepository.findTop200ByMembershipExpiresAtLessThanEqualOrderByMembershipExpiresAtAsc(now);
        for (AppUser user : expiredUsers) {
            normalizeExpiredMembership(user, now);
        }
        if (!expiredUsers.isEmpty()) {
            userRepository.saveAll(expiredUsers);
        }
    }

    private AuthToken newToken(
            AppUser user,
            String rawToken,
            String tokenType,
            String familyId,
            Instant expiresAt,
            String clientIp,
            String userAgent,
            Instant lastUsedAt
    ) {
        AuthToken token = new AuthToken();
        token.setUser(user);
        token.setTokenHash(hashToken(rawToken));
        token.setTokenType(tokenType);
        token.setFamilyId(familyId);
        token.setExpiresAt(expiresAt);
        token.setClientIp(safeMetadata(clientIp, 64));
        token.setUserAgent(safeMetadata(userAgent, 255));
        token.setLastUsedAt(lastUsedAt);
        token.setCreatedAt(lastUsedAt);
        return token;
    }

    private AuthToken requireStoredAccessToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new SecurityException("请先登录");
        }
        AuthToken stored = authTokenRepository
                .findByTokenHashAndTokenType(hashToken(rawToken), AuthToken.ACCESS)
                .orElseThrow(() -> new SecurityException("登录状态已失效，请重新登录"));
        validateActiveToken(stored, "登录状态已过期，请重新登录");
        if (!Boolean.TRUE.equals(stored.getUser().getEnabled())) {
            throw new SecurityException("账号已被禁用");
        }
        return stored;
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

    private void mergeSessionToken(
            LinkedHashMap<String, Map<String, Object>> families,
            AuthToken token,
            String currentFamilyId,
            Instant now
    ) {
        if (token == null
                || token.getFamilyId() == null
                || token.getRevokedAt() != null
                || token.getExpiresAt() == null
                || !token.getExpiresAt().isAfter(now)) {
            return;
        }
        Map<String, Object> session = families.computeIfAbsent(
                token.getFamilyId(),
                ignored -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", token.getFamilyId());
                    item.put("current", token.getFamilyId().equals(currentFamilyId));
                    item.put("clientIp", safeMetadata(token.getClientIp(), 64));
                    item.put("userAgent", safeMetadata(token.getUserAgent(), 255));
                    item.put("createdAt", token.getCreatedAt());
                    item.put("lastUsedAt", token.getLastUsedAt());
                    item.put("expiresAt", token.getExpiresAt());
                    return item;
                }
        );
        if (String.valueOf(session.get("clientIp")).isBlank()) {
            session.put("clientIp", safeMetadata(token.getClientIp(), 64));
        }
        if (String.valueOf(session.get("userAgent")).isBlank()) {
            session.put("userAgent", safeMetadata(token.getUserAgent(), 255));
        }
        session.put("createdAt", earlier((Instant) session.get("createdAt"), token.getCreatedAt()));
        session.put("lastUsedAt", later((Instant) session.get("lastUsedAt"), token.getLastUsedAt()));
        session.put("expiresAt", later((Instant) session.get("expiresAt"), token.getExpiresAt()));
    }

    private Instant earlier(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private String safeMetadata(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
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

    private int membershipRank(String role) {
        if (role == null) return 0;
        return switch (role.trim().toUpperCase()) {
            case "VIP" -> 1;
            case "SVIP" -> 2;
            case "ADMIN" -> 3;
            default -> 0;
        };
    }

    private boolean normalizeExpiredMembership(AppUser user, Instant now) {
        if (user == null
                || isAdmin(user)
                || user.getMembershipExpiresAt() == null
                || user.getMembershipExpiresAt().isAfter(now)) {
            return false;
        }
        String fallbackRole = normalizeMembershipFallbackRole(user.getMembershipFallbackRole());
        long fallbackQuota = user.getMembershipFallbackQuotaBytes() == null
                ? defaultQuotaBytes
                : normalizeQuota(user.getMembershipFallbackQuotaBytes());
        user.setRole(fallbackRole);
        user.setQuotaBytes(fallbackQuota);
        clearTemporaryMembership(user);
        return true;
    }

    private String normalizeMembershipFallbackRole(String role) {
        String normalized = normalizeRole(role);
        return "ADMIN".equals(normalized) ? "USER" : normalized;
    }

    private void clearTemporaryMembership(AppUser user) {
        user.setMembershipExpiresAt(null);
        user.setMembershipFallbackRole(null);
        user.setMembershipFallbackQuotaBytes(null);
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
