package com.cloudmind.demo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ai_config")
public class AiConfig {
    @Id
    private Long id = 1L;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(nullable = false, length = 80)
    private String provider = "DeepSeek / OpenAI 兼容";

    @Column(nullable = false, length = 255)
    private String baseUrl = "https://api.deepseek.com/v1";

    @Column(nullable = false, length = 120)
    private String model = "deepseek-chat";

    @Column(length = 512)
    private String apiKey;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reviewPrompt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = 1L;
        if (enabled == null) enabled = false;
        if (provider == null || provider.isBlank()) provider = "DeepSeek / OpenAI 兼容";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.deepseek.com/v1";
        if (model == null || model.isBlank()) model = "deepseek-chat";
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getReviewPrompt() { return reviewPrompt; }
    public void setReviewPrompt(String reviewPrompt) { this.reviewPrompt = reviewPrompt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
