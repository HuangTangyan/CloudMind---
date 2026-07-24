package com.cloudmind.demo.config;

import jakarta.servlet.ServletRequest;
import com.cloudmind.demo.entity.AuthToken;
import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.repository.AppUserRepository;
import com.cloudmind.demo.repository.AuthTokenRepository;
import com.cloudmind.demo.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigurationTest {
    @Test
    void applicationProfilesAreValidYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : List.of("application.yml", "application-dev.yml", "application-prod.yml")) {
            assertFalse(loader.load(file, new ClassPathResource(file)).isEmpty(), file);
        }
    }

    @Test
    void bearerAuthorizationHeaderIsAvailableToLegacyControllers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secure-session-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedToken = new AtomicReference<>();
        AppUserRepository userRepository = mock(AppUserRepository.class);
        AuthTokenRepository tokenRepository = mock(AuthTokenRepository.class);
        AuthService authService = new AuthService(
                userRepository,
                tokenRepository,
                new BCryptPasswordEncoder(4)
        );
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("secure-user");
        user.setRole("USER");
        user.setEnabled(true);
        AuthToken storedToken = new AuthToken();
        storedToken.setUser(user);
        storedToken.setTokenType(AuthToken.ACCESS);
        storedToken.setTokenHash("a".repeat(64));
        storedToken.setFamilyId("test-family");
        storedToken.setExpiresAt(Instant.now().plusSeconds(60));
        when(tokenRepository.findByTokenHashAndTokenType(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(AuthToken.ACCESS)
        )).thenReturn(Optional.of(storedToken));

        new BearerTokenHeaderFilter(authService).doFilter(request, response, (servletRequest, servletResponse) ->
                capturedToken.set(((ServletRequest) servletRequest) instanceof MockHttpServletRequest
                        ? ((MockHttpServletRequest) servletRequest).getHeader("X-Token")
                        : ((jakarta.servlet.http.HttpServletRequest) servletRequest).getHeader("X-Token"))
        );

        assertEquals("secure-session-token", capturedToken.get());
    }
}
