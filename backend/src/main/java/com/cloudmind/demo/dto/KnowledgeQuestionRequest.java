package com.cloudmind.demo.dto;

import java.util.List;

public class KnowledgeQuestionRequest {
    private String question;
    private Integer topK;
    // ALL=全部文件，FILE=指定文件，FOLDER=指定文件夹
    private String scopeType;
    private Long scopeId;
    // 指定文件模式支持多选文件
    private List<Long> scopeIds;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }
    public List<Long> getScopeIds() { return scopeIds; }
    public void setScopeIds(List<Long> scopeIds) { this.scopeIds = scopeIds; }
}
