package com.cloudmind.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdAndExceptionHandlingTest {
    @Test
    void preservesSafeRequestIdAndReturnsItInResponse() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files");
        request.addHeader(RequestIdFilter.HEADER, "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                assertEquals(
                        "client-request-123",
                        filteredRequest.getAttribute(RequestIdFilter.ATTRIBUTE)
                ));

        assertEquals("client-request-123", response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files");
        request.addHeader(RequestIdFilter.HEADER, "bad\r\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {});

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertNotNull(generated);
        assertTrue(generated.matches("[a-f0-9]{32}"));
    }

    @Test
    void unexpectedExceptionResponseDoesNotExposeDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "request-987654");

        ResponseEntity<Map<String, Object>> response = handler.handleOther(
                new IllegalStateException("password=secret-value; host=internal-db"),
                request
        );

        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("request-987654", response.getBody().get("requestId"));
        String message = String.valueOf(response.getBody().get("message"));
        assertTrue(message.contains("request-987654"));
        assertFalse(message.contains("secret-value"));
        assertFalse(message.contains("internal-db"));
    }
}
