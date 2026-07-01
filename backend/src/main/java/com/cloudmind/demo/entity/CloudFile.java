package com.cloudmind.demo.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cloud_file", indexes = {
        @Index(name = "idx_cloud_file_owner_parent", columnList = "owner_id,parent_id"),
        @Index(name = "idx_cloud_file_name", columnList = "name"),
        @Index(name = "idx_cloud_file_deleted", columnList = "deleted")
})
public class CloudFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileKind kind;

    @Column(length = 1024)
    private String objectName;

    @Column(length = 255)
    private String contentType;

    @Column(nullable = false)
    private Long sizeBytes = 0L;

    @Column(nullable = false)
    private Boolean deleted = false;

    private Instant deletedAt;

    @Column(length = 500)
    private String summary;

    @Column(length = 500)
    private String tags;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String extractedText;

    // 预留给后续 AI 内容审查：NORMAL=正常，ABNORMAL=异常，PENDING=待审查
    @Column(nullable = false, length = 20)
    private String reviewStatus = "NORMAL";

    @Column(length = 500)
    private String reviewNote;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (deleted == null) deleted = false;
        if (sizeBytes == null) sizeBytes = 0L;
        if (reviewStatus == null) reviewStatus = "NORMAL";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AppUser getOwner() { return owner; }
    public void setOwner(AppUser owner) { this.owner = owner; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public FileKind getKind() { return kind; }
    public void setKind(FileKind kind) { this.kind = kind; }
    public String getObjectName() { return objectName; }
    public void setObjectName(String objectName) { this.objectName = objectName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
