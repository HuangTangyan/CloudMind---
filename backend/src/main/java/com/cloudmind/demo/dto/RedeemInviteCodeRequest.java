package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RedeemInviteCodeRequest {
    @NotBlank(message = "请输入邀请码")
    @Size(max = 96, message = "邀请码格式不正确")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
