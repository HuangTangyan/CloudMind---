package com.cloudmind.demo.config;

import com.cloudmind.demo.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    BearerTokenHeaderFilter bearerTokenHeaderFilter(AuthService authService) {
        return new BearerTokenHeaderFilter(authService);
    }

    @Bean
    ApiRateLimitFilter apiRateLimitFilter(ObjectMapper objectMapper) {
        return new ApiRateLimitFilter(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenHeaderFilter bearerTokenHeaderFilter,
            ApiRateLimitFilter apiRateLimitFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; "
                                        + "base-uri 'none'; "
                                        + "frame-ancestors 'none'; "
                                        + "form-action 'self'; "
                                        + "img-src 'self' data: blob:; "
                                        + "media-src 'self' blob:; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; "
                                        + "connect-src 'self'"
                        ))
                        .permissionsPolicy(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
                        )))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/capabilities",
                                "/api/auth/refresh",
                                "/api/auth/logout"
                        ).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(request, response, objectMapper, 401, "请先登录或重新登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityError(request, response, objectMapper, 403, "没有访问该资源的权限")))
                .addFilterBefore(bearerTokenHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiRateLimitFilter, BearerTokenHeaderFilter.class);
        return http.build();
    }

    private static void writeSecurityError(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", status);
        body.put("message", message);
        body.put("requestId", RequestIdFilter.current(request));
        body.put("timestamp", Instant.now().toString());
        objectMapper.writeValue(
                response.getOutputStream(),
                body
        );
    }
}
