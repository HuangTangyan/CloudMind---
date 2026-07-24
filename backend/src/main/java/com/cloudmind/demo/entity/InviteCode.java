package com.cloudmind.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "invite_code", indexes = {
        @Index(name = "idx_invite_code_hash", columnList = "code_hash", unique = true),
        @Index(name = "idx_invite_code_batch", columnList = "batch_id"),
        @Index(name = "idx_invite_code_redeemed", columnList = "redeemed_at")
})
public class InviteCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private InviteCodeBatch batch;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redeemed_by_id")
    private AppUser redeemedBy;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (version == null) version = 0L;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public InviteCodeBatch getBatch() { return batch; }
    public void setBatch(InviteCodeBatch batch) { this.batch = batch; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public AppUser getRedeemedBy() { return redeemedBy; }
    public void setRedeemedBy(AppUser redeemedBy) { this.redeemedBy = redeemedBy; }
    public Instant getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(Instant redeemedAt) { this.redeemedAt = redeemedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
