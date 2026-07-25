package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class BatchDeleteUsersRequest {
    @NotEmpty(message = "请至少选择一个账号")
    @Size(max = 100, message = "每批最多删除 100 个账号")
    private List<@NotNull(message = "用户 ID 不能为空") Long> userIds;

    @NotBlank(message = "管理员密码不能为空")
    private String currentPassword;

    @NotBlank(message = "请输入删除确认文本")
    private String confirmText;

    public List<Long> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<Long> userIds) {
        this.userIds = userIds;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getConfirmText() {
        return confirmText;
    }

    public void setConfirmText(String confirmText) {
        this.confirmText = confirmText;
    }
}
