package com.cloudmind.demo.controller;

import com.cloudmind.demo.dto.ChangePasswordRequest;
import com.cloudmind.demo.dto.LoginRequest;
import com.cloudmind.demo.dto.LogoutRequest;
import com.cloudmind.demo.dto.RefreshTokenRequest;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody LoginRequest request) {
        AppUser user = authService.register(request.getUsername(), request.getPassword());
        Map<String, Object> loginResult = authService.login(user.getUsername(), request.getPassword());
        return Map.of("success", true, "message", "注册成功", "data", loginResult);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return Map.of("success", true, "message", "登录成功", "data", authService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Map.of(
                "success", true,
                "message", "登录状态已刷新",
                "data", authService.refreshSession(request.getRefreshToken())
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
