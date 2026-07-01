package com.cloudmind.demo.dto;

public class MoveCopyRequest {
    private Long targetParentId;
    private Long parentId;

    public Long getTargetParentId() { return targetParentId; }
    public void setTargetParentId(Long targetParentId) { this.targetParentId = targetParentId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Long effectiveParentId() {
        return targetParentId != null ? targetParentId : parentId;
    }
}
