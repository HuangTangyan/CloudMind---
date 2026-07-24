package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RevokeInviteBatchRequest {
    @NotBlank(message = "撤销原因不能为空")
    @Size(max = 200, message = "撤销原因不能超过 200 个字符")
    private String reason;

    @NotBlank(message = "请输入当前管理员密码")
    @Size(max = 72, message = "管理员密码格式不正确")
    private String currentPassword;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
}
