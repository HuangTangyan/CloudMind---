package com.cloudmind.demo.config;

import com.cloudmind.demo.entity.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRateLimitFilterTest {
    @Test
    void blocksLoginAttemptsAfterConfiguredLimit() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(new ObjectMapper(), clock);
        ReflectionTestUtils.setField(filter, "loginPerMinute", 2);
        AtomicInteger allowedRequests = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("192.0.2.10");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    allowedRequests.incrementAndGet());

            if (i == 2) {
                assertEquals(429, response.getStatus());
                assertTrue(response.getHeader("Retry-After") != null);
                assertTrue(response.getContentAsString().contains("请求过于频繁"));
            }
        }

        assertEquals(2, allowedRequests.get());
    }

    @Test
    void authenticatedUploadIsLimitedByUserAndIp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC);
        ApiRateLimitFilter filter = new ApiRateLimitFilter(new ObjectMapper(), clock);
        ReflectionTestUtils.setField(filter, "uploadPerMinute", 1);
        AppUser user = new AppUser();
        user.setId(42L);
        user.setUsername("alice");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null)
        );
        AtomicInteger allowedRequests = new AtomicInteger();

        try {
            MockHttpServletRequest first = new MockHttpServletRequest(
                    "POST",
                    "/api/files/upload"
            );
            first.setRemoteAddr("198.51.100.10");
            filter.doFilter(
                    first,
                    new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> allowedRequests.incrementAndGet()
            );

            MockHttpServletRequest second = new MockHttpServletRequest(
                    "POST",
                    "/api/files/upload"
            );
            second.setRemoteAddr("198.51.100.11");
            MockHttpServletResponse blocked = new MockHttpServletResponse();
            filter.doFilter(
                    second,
                    blocked,
                    (ignoredRequest, ignoredResponse) -> allowedRequests.incrementAndGet()
            );

            assertEquals(1, allowedRequests.get());
            assertEquals(429, blocked.getStatus());
            assertTrue(blocked.getHeader("Retry-After") != null);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
