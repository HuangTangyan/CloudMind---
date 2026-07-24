package com.cloudmind.demo.service;

public class LoginLockedException extends SecurityException {
    private final long retryAfterSeconds;

    public LoginLockedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
