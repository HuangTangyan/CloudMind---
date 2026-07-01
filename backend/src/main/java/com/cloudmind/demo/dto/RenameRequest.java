package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;

public class RenameRequest {
    @NotBlank(message = "新名称不能为空")
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
