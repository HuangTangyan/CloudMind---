package com.cloudmind.demo.dto;

import jakarta.validation.constraints.Size;

public class LogoutRequest {
    @Size(max = 512, message = "刷新令牌格式无效")
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
