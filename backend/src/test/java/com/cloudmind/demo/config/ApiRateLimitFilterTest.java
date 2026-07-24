package com.cloudmind.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
}
