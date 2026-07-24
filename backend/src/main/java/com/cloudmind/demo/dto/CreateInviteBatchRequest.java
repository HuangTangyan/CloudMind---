package com.cloudmind.demo.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class CreateInviteBatchRequest {
    @NotBlank(message = "会员等级不能为空")
    private String role;

    @Min(value = 1, message = "生成数量不能少于 1")
    @Max(value = 500, message = "单批最多生成 500 个邀请码")
    private int count;

    @NotNull(message = "有效期不能为空")
    @Future(message = "有效期必须晚于当前时间")
    private Instant expiresAt;

    @Size(max = 200, message = "批次备注不能超过 200 个字符")
    private String note;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
