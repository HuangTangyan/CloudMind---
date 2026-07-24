package com.cloudmind.demo.controller;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import com.cloudmind.demo.service.MembershipEntitlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/membership")
public class MembershipController {
    private final AuthService authService;
    private final MembershipEntitlementService entitlementService;

    public MembershipController(
            AuthService authService,
            MembershipEntitlementService entitlementService
    ) {
        this.authService = authService;
        this.entitlementService = entitlementService;
    }

    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestHeader(value = "X-Token", required = false) String token
    ) {
        AppUser user = authService.requireUser(token);
        return Map.of("success", true, "data", entitlementService.summary(user));
    }
}
