package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.ChangePasswordRequest;
import com.cloudmind.demo.dto.LoginRequest;
import com.cloudmind.demo.dto.LogoutRequest;
import com.cloudmind.demo.dto.RefreshTokenRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    @Value("${cloudmind.registration.enabled:true}")
    private boolean registrationEnabled;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!registrationEnabled) {
            throw new com.cloudmind.demo.service.RegistrationClosedException(
                    "校园内测期间已关闭公开注册，请联系管理员获取测试账号"
            );
        }
        AppUser user = authService.register(request.getUsername(), request.getPassword());
        Map<String, Object> loginResult = authService.login(
                user.getUsername(),
                request.getPassword(),
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader("User-Agent")
        );
        return Map.of("success", true, "message", "注册成功", "data", loginResult);
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "success", true,
                "data", Map.of(
                        "registrationEnabled", registrationEnabled,
                        "deploymentMode", registrationEnabled ? "PUBLIC_REGISTRATION" : "CAMPUS_INVITE_ONLY"
                )
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        return Map.of(
                "success", true,
                "message", "登录成功",
                "data", authService.login(
                        request.getUsername(),
                        request.getPassword(),
                        servletRequest.getRemoteAddr(),
                        servletRequest.getHeader("User-Agent")
                )
        );
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest servletRequest
    ) {
        return Map.of(
                "success", true,
                "message", "登录状态已刷新",
                "data", authService.refreshSession(
                        request.getRefreshToken(),
                        servletRequest.getRemoteAddr(),
                        servletRequest.getHeader("User-Agent")
                )
        );
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "X-Token", required = false) String token,
            @Valid @RequestBody(required = false) LogoutRequest request
    ) {
        authService.logout(token, request == null ? null : request.getRefreshToken());
        return Map.of("success", true, "message", "已安全退出");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Token", required = false) String token) {
        AppUser user = authService.requireSessionUser(token);
        return Map.of("success", true, "data", authService.toUserMap(user));
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(
            @RequestHeader(value = "X-Token", required = false) String token
    ) {
        List<Map<String, Object>> sessions = authService.sessions(token);
        return Map.of("success", true, "data", sessions);
    }

    @DeleteMapping("/sessions/{familyId}")
    public Map<String, Object> revokeSession(
            @RequestHeader(value = "X-Token", required = false) String token,
            @PathVariable String familyId
    ) {
        authService.revokeSession(token, familyId);
        return Map.of("success", true, "message", "设备会话已下线");
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @RequestHeader(value = "X-Token", required = false) String token,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        return Map.of(
                "success", true,
                "message", "密码修改成功",
                "data", authService.changePassword(token, request.getCurrentPassword(), request.getNewPassword())
        );
    }
}
