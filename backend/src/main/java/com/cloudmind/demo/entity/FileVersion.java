package com.cloudmind.demo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "file_version", indexes = {
        @Index(name = "idx_file_version_file", columnList = "file_id")
})
public class FileVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private CloudFile file;

    @Column(nullable = false, length = 1024)
    private String objectName;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String contentType;

    @Column(nullable = false)
    private Long sizeBytes = 0L;

    @Column(length = 500)
    private String summary;

    @Column(length = 500)
    private String tags;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String extractedText;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (sizeBytes == null) sizeBytes = 0L;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public CloudFile getFile() { return file; }
    public void setFile(CloudFile file) { this.file = file; }
    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
