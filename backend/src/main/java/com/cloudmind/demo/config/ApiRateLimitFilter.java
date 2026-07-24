package com.cloudmind.demo.config;

import com.cloudmind.demo.entity.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    @Value("${cloudmind.rate-limit.enabled:true}")
    private boolean enabled = true;

    @Value("${cloudmind.rate-limit.login-per-minute:5}")
    private int loginPerMinute = 5;

    @Value("${cloudmind.rate-limit.register-per-hour:3}")
    private int registerPerHour = 3;

    @Value("${cloudmind.rate-limit.refresh-per-minute:30}")
    private int refreshPerMinute = 30;

    @Value("${cloudmind.rate-limit.password-change-per-15-minutes:5}")
    private int passwordChangePer15Minutes = 5;

    @Value("${cloudmind.rate-limit.invite-redeem-per-15-minutes:10}")
    private int inviteRedeemPer15Minutes = 10;

    @Value("${cloudmind.rate-limit.upload-per-minute:30}")
    private int uploadPerMinute = 30;

    @Value("${cloudmind.rate-limit.ai-per-minute:30}")
    private int aiPerMinute = 30;

    public ApiRateLimitFilter(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    ApiRateLimitFilter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Policy policy = resolvePolicy(request);
        if (!enabled || policy == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.millis();
        long retryAfterSeconds = 0L;
        for (String clientKey : clientKeys(request, policy)) {
            WindowState state = consume(policy.name() + ":" + clientKey, policy, now);
            if (state.count() > policy.limit()) {
                retryAfterSeconds = Math.max(
                        retryAfterSeconds,
                        Math.max(
                                1L,
                                (policy.windowMillis() - (now - state.windowStartedAt()) + 999L) / 1000L
                        )
                );
            }
        }

        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry ->
                    now - entry.getValue().windowStartedAt() > 3_600_000L);
        }

        if (retryAfterSeconds > 0L) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    Map.of("success", false, "message", "请求过于频繁，请稍后再试")
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private WindowState consume(String key, Policy policy, long now) {
        return windows.compute(key, (ignored, old) -> {
            if (old == null || now - old.windowStartedAt() >= policy.windowMillis()) {
                return new WindowState(now, 1);
            }
            return new WindowState(old.windowStartedAt(), old.count() + 1);
        });
    }

    private Policy resolvePolicy(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;
        String path = request.getRequestURI();
        if ("/api/auth/login".equals(path)) {
            return new Policy("login", positive(loginPerMinute), 60_000L, false);
        }
        if ("/api/auth/register".equals(path)) {
            return new Policy("register", positive(registerPerHour), 3_600_000L, false);
        }
        if ("/api/auth/refresh".equals(path)) {
            return new Policy("refresh", positive(refreshPerMinute), 60_000L, false);
        }
        if ("/api/auth/change-password".equals(path)) {
            return new Policy("password-change", positive(passwordChangePer15Minutes), 900_000L, true);
        }
        if ("/api/invites/redeem".equals(path)) {
            return new Policy("invite-redeem", positive(inviteRedeemPer15Minutes), 900_000L, true);
        }
        if (path.startsWith("/api/files/upload")) {
            return new Policy("upload", positive(uploadPerMinute), 60_000L, true);
        }
        if ("/api/knowledge/ask".equals(path) || "/api/knowledge/overview".equals(path)) {
            return new Policy("ai", positive(aiPerMinute), 60_000L, true);
        }
        return null;
    }

    private List<String> clientKeys(HttpServletRequest request, Policy policy) {
        String addressKey = "ip-" + remoteAddress(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            String userKey = "user-" + user.getId();
            return policy.dualDimension()
                    ? List.of(userKey, addressKey)
                    : List.of(userKey);
        }
        return List.of(addressKey);
    }

    private String remoteAddress(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) return "unknown";
        return remoteAddress.length() > 80 ? remoteAddress.substring(0, 80) : remoteAddress;
    }

    private int positive(int value) {
        return Math.max(1, value);
    }

    private record Policy(String name, int limit, long windowMillis, boolean dualDimension) {}
    private record WindowState(long windowStartedAt, int count) {}
}
