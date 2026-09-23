package com.example.starter.signing.domain;

import java.time.Instant;

import com.example.starter.signing.model.PackageStatus;

/**
 * 签署包主表 {@code signing_package} 对应实体。
 */
public class SigningPackage {

    /** 签署包 ID（UUID）。 */
    private String id;
    /** 当前修订号，从 1 开始。 */
    private int currentRevision;
    /** 当前修订文档的 SHA-256 十六进制摘要。 */
    private String documentHash;
    /** 流程 UTC 到期时间。 */
    private Instant expiresAt;
    /** 流程状态。 */
    private PackageStatus status;
    /** 证据包冻结时间（UTC），未冻结为 null。 */
    private Instant frozenAt;
    /** 冻结证据包摘要，未冻结为 null。 */
    private String evidenceDigest;
    /** 建包时间（UTC）。 */
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(int currentRevision) {
        this.currentRevision = currentRevision;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public PackageStatus getStatus() {
        return status;
    }

    public void setStatus(PackageStatus status) {
        this.status = status;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Instant frozenAt) {
        this.frozenAt = frozenAt;
    }

    public String getEvidenceDigest() {
        return evidenceDigest;
    }

    public void setEvidenceDigest(String evidenceDigest) {
        this.evidenceDigest = evidenceDigest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
