package com.cloudmind.demo.service;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final AppUserRepository userRepository;
    private final Map<String, Long> tokenStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${cloudmind.demo.default-quota-bytes:10737418240}")
    private long defaultQuotaBytes;

    public AuthService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
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
        setPassword(user, password);
        user.setRole("USER");
        user.setQuotaBytes(defaultQuotaBytes);
        return userRepository.save(user);
    }

    public Map<String, Object> login(String username, String password) {
        username = normalizeUsername(username);
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalArgumentException("账号已被禁用");
        }
        if (!hash(password, user.getSalt()).equals(user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((user.getId() + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        tokenStore.put(token, user.getId());
        return Map.of("token", token, "user", toUserMap(user));
    }

    public AppUser requireUser(String token) {
        if (token == null || token.isBlank()) {
            throw new SecurityException("请先登录");
        }
        Long userId = tokenStore.get(token);
        if (userId == null) {
            throw new SecurityException("登录状态已失效，请重新登录");
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new SecurityException("用户不存在"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new SecurityException("账号已被禁用");
        }
        return user;
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
                "enabled", user.getEnabled()
        );
    }

    @Transactional
    public void ensureAdmin(String username, String password) {
        String normalized = normalizeUsername(username);
        Optional<AppUser> old = userRepository.findByUsername(normalized);
        if (old.isPresent()) return;
        AppUser admin = new AppUser();
        admin.setUsername(normalized);
        setPassword(admin, password);
        admin.setRole("ADMIN");
        admin.setQuotaBytes(defaultQuotaBytes);
        userRepository.save(admin);
    }

    @Transactional
    public AppUser createUserByAdmin(String username, String password, String role, Long quotaBytes) {
        username = normalizeUsername(username);
        validatePassword(password);
        if (userRepository.existsByUsername(username)) throw new IllegalArgumentException("用户名已存在");
        AppUser user = new AppUser();
        user.setUsername(username);
        setPassword(user, password);
        user.setRole(normalizeRole(role));
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
        return userRepository.save(user);
    }

    @Transactional
    public String resetPasswordByAdmin(AppUser admin, Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (admin.getId().equals(user.getId())) throw new IllegalArgumentException("为了避免误操作，管理员不能在这里重置自己的密码");
        String newPassword = randomPassword();
        setPassword(user, newPassword);
        userRepository.save(user);
        return newPassword;
    }

    private void setPassword(AppUser user, String password) {
        validatePassword(password);
        user.setSalt(newSalt());
        user.setPasswordHash(hash(password, user.getSalt()));
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
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new IllegalArgumentException("密码长度应为 6-64 位");
        }
    }

    private String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder("CM-");
        for (int i = 0; i < 10; i++) {
            sb.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String newSalt() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }
}
