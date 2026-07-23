package com.cloudmind.demo.config;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

        new BearerTokenHeaderFilter().doFilter(request, response, (servletRequest, servletResponse) ->
                capturedToken.set(((ServletRequest) servletRequest) instanceof MockHttpServletRequest
                        ? ((MockHttpServletRequest) servletRequest).getHeader("X-Token")
                        : ((jakarta.servlet.http.HttpServletRequest) servletRequest).getHeader("X-Token"))
        );

        assertEquals("secure-session-token", capturedToken.get());
    }
}
