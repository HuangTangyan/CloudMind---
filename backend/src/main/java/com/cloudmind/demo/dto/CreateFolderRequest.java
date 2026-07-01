package com.cloudmind.demo.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateFolderRequest {
    private Long parentId;
    @NotBlank(message = "文件夹名称不能为空")
    private String name;

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
