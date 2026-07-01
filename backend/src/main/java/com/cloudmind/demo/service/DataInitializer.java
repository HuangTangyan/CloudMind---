package com.cloudmind.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {
    private final AuthService authService;

    @Value("${cloudmind.demo.admin-username:admin}")
    private String adminUsername;

    @Value("${cloudmind.demo.admin-password:123456}")
    private String adminPassword;

    public DataInitializer(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        authService.ensureAdmin(adminUsername, adminPassword);
    }
}
