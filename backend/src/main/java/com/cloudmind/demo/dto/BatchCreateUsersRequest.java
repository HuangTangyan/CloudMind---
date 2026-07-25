package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class BatchCreateUsersRequest {
    @NotEmpty(message = "请至少填写一个临时用户名")
    @Size(max = 200, message = "每批最多创建 200 个账号")
    private List<
            @NotBlank(message = "临时用户名不能为空")
            @Size(max = 32, message = "临时用户名长度不能超过 32 位")
            String> usernames;

    @NotBlank(message = "管理员密码不能为空")
    private String currentPassword;

    private String role = "USER";

    private Long quotaBytes;

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public void setQuotaBytes(Long quotaBytes) {
        this.quotaBytes = quotaBytes;
    }
}
