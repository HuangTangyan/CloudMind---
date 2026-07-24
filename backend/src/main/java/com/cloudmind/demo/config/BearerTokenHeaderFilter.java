package com.cloudmind.demo.config;

import com.cloudmind.demo.entity.AppUser;
import com.cloudmind.demo.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class BearerTokenHeaderFilter extends OncePerRequestFilter {
    private final AuthService authService;

    public BearerTokenHeaderFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String legacyToken = request.getHeader("X-Token");
        String token = null;
        boolean bearerHeaderPresent = authorization != null && !authorization.isBlank();
        if (bearerHeaderPresent && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        } else if (!bearerHeaderPresent && legacyToken != null && !legacyToken.isBlank()) {
            token = legacyToken.trim();
        }

        HttpServletRequest effectiveRequest = request;
        if (token != null && !token.isBlank()) {
            String selectedToken = token;
            effectiveRequest = new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("X-Token".equalsIgnoreCase(name)) return selectedToken;
                    return super.getHeader(name);
                }
            };

            try {
                AppUser user = authService.requireSessionUser(token);
                String role = user.getRole() == null ? "USER" : user.getRole().toUpperCase();
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (SecurityException ignored) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(effectiveRequest, response);
    }
}
