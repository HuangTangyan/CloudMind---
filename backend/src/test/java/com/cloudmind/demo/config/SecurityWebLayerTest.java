package com.cloudmind.demo.config;

import com.cloudmind.demo.controller.AuthController;
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

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityWebLayerTest.TestBeans.class})
class SecurityWebLayerTest {
    @Autowired
    private MockMvc mockMvc;

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
