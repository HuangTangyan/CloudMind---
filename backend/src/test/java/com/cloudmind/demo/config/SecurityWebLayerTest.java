package com.cloudmind.demo.config;

import com.cloudmind.demo.controller.AuthController;
import com.cloudmind.demo.entity.AuthToken;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.AuthTokenRepository;
import com.cloudmind.demo.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityWebLayerTest.TestBeans.class})
@TestPropertySource(properties = "cloudmind.registration.enabled=false")
class SecurityWebLayerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthTokenRepository authTokenRepository;

    @Test
    void protectedApiRejectsAnonymousRequestsWithJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void loginEndpointRemainsPublicAndUsesRequestValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicCapabilitiesExposeInviteOnlyMode() throws Exception {
        mockMvc.perform(get("/api/auth/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationEnabled").value(false))
                .andExpect(jsonPath("$.data.deploymentMode").value("CAMPUS_INVITE_ONLY"));
    }

    @Test
    void inviteOnlyModeRejectsOtherwiseValidPublicRegistration() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student01","password":"safe-password"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void ordinaryUserCannotEnterAdminApi() throws Exception {
        AppUser user = new AppUser();
        user.setId(5L);
        user.setUsername("ordinary-user");
        user.setRole("USER");
        user.setEnabled(true);
        user.setPasswordChangedAt(Instant.now());
        AuthToken token = new AuthToken();
        token.setUser(user);
        token.setTokenType(AuthToken.ACCESS);
        token.setFamilyId("ordinary-user-family");
        token.setExpiresAt(Instant.now().plusSeconds(60));
        when(authTokenRepository.findByTokenHashAndTokenType(
                anyString(),
                eq(AuthToken.ACCESS)
        )).thenReturn(Optional.of(token));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer ordinary-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:5173"
                ));

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void secureResponsesContainDeploymentSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        containsString("max-age=31536000")
                ))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("frame-ancestors 'none'")
                ))
                .andExpect(header().string(
                        "Permissions-Policy",
                        containsString("camera=()")
                ))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        AppUserRepository appUserRepository() {
            return mock(AppUserRepository.class);
        }

        @Bean
        AuthTokenRepository authTokenRepository() {
            return mock(AuthTokenRepository.class);
        }

        @Bean
        AuthService authService(
                AppUserRepository userRepository,
                AuthTokenRepository tokenRepository
        ) {
            return new AuthService(
                    userRepository,
                    tokenRepository,
                    new BCryptPasswordEncoder(4)
            );
        }
    }
}
