package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CompleteFirstLoginRequest {
    @NotBlank(message = "当前密码不能为空")
    private String currentPassword;

    @Size(max = 32, message = "新用户名长度不能超过 32 位")
    private String newUsername;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 72, message = "新密码长度应为 6-72 位")
    private String newPassword;

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewUsername() {
        return newUsername;
    }

    public void setNewUsername(String newUsername) {
        this.newUsername = newUsername;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
