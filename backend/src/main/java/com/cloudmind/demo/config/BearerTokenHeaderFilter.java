package com.cloudmind.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class BearerTokenHeaderFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String legacyToken = request.getHeader("X-Token");
        String authorization = request.getHeader("Authorization");
        if ((legacyToken == null || legacyToken.isBlank())
                && authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearerToken = authorization.substring(7).trim();
            if (!bearerToken.isBlank()) {
                HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("X-Token".equalsIgnoreCase(name)) {
                            return bearerToken;
                        }
                        return super.getHeader(name);
                    }
                };
                filterChain.doFilter(wrapped, response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
