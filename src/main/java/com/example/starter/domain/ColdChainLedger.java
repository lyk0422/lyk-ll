package com.example.starter.domain;

import java.time.Instant;

/**
 * 冷链运输台账 PO：受控包装子批的运输证据版本链头（一子批一本台账）。
 * 版本从 1 起，每次成功补录/更正加 1；温证仅在 certVersion 等于 version 且状态合格时有效。
 */
public class ColdChainLedger {

    private Long id;
    private Long reviewId;
    private String batchNo;
    private ColdChainStatus status;
    /** 证据版本号：登记为 1，每次成功补录/更正加 1。 */
    private int version;
    /** 最近一次合格冻结的证据版本；仅当等于 version 时温证有效，空值表示从未合格。 */
    private Integer certVersion;
    /** 完整运输起点 UTC 精确时刻（首片段 start，随版本冻结）。 */
    private Instant transportStart;
    /** 完整运输终点 UTC 精确时刻（末片段 end，随版本冻结）。 */
    private Instant transportEnd;
    /** 当前版本证据提交精确时刻（UTC）。 */
    private Instant submittedAt;
    /** 当前版本合格冻结时刻，空值表示当前版本未合格。 */
    private Instant qualifiedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public ColdChainStatus getStatus() {
        return status;
    }

    public void setStatus(ColdChainStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Integer getCertVersion() {
        return certVersion;
    }

    public void setCertVersion(Integer certVersion) {
        this.certVersion = certVersion;
    }

    public Instant getTransportStart() {
        return transportStart;
    }

    public void setTransportStart(Instant transportStart) {
        this.transportStart = transportStart;
    }

    public Instant getTransportEnd() {
        return transportEnd;
    }

    public void setTransportEnd(Instant transportEnd) {
        this.transportEnd = transportEnd;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getQualifiedAt() {
        return qualifiedAt;
    }

    public void setQualifiedAt(Instant qualifiedAt) {
        this.qualifiedAt = qualifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 温证当前是否有效：状态合格且冻结版本等于当前版本。 */
    public boolean isCertValid() {
        return status == ColdChainStatus.QUALIFIED
                && certVersion != null && certVersion == version;
    }
}
