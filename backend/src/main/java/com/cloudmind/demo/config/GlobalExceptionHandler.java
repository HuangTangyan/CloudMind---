package com.cloudmind.demo.config;

import com.cloudmind.demo.service.LoginLockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, safeMessage(ex.getMessage(), "请求参数不正确"), request);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(
            SecurityException ex,
            HttpServletRequest request
    ) {
        return error(HttpStatus.UNAUTHORIZED, safeMessage(ex.getMessage(), "登录状态无效"), request);
    }

    @ExceptionHandler(LoginLockedException.class)
    public ResponseEntity<Map<String, Object>> handleLoginLocked(
            LoginLockedException ex,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(errorBody(
                        HttpStatus.TOO_MANY_REQUESTS,
                        safeMessage(ex.getMessage(), "登录尝试过多，请稍后再试"),
                        request
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String message = ex.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败"
                : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return error(HttpStatus.BAD_REQUEST, safeMessage(message, "参数校验失败"), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "请求内容格式不正确", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request
    ) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传内容超过服务器允许的大小", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(
            Exception ex,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.current(request);
        log.error(
                "Unhandled API exception, requestId={}, type={}",
                requestId,
                ex.getClass().getName()
        );
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部错误，请稍后重试。问题编号：" + requestId,
                request
        );
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(errorBody(status, message, request));
    }

    private Map<String, Object> errorBody(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", status.value());
        body.put("message", message);
        body.put("requestId", RequestIdFilter.current(request));
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    private String safeMessage(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        String cleaned = message
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\p{Cntrl}", "")
                .trim();
        if (cleaned.isBlank()) return fallback;
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }
}
