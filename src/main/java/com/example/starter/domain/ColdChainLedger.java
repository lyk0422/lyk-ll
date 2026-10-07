package com.example.starter.domain;

import java.time.Instant;

/**
 * 冷链运输台账 PO。受控包装子批登记运输片段后即启用冷链签发门禁；
 * 版本从 1 起，每次成功补录/更正加 1 并使旧温证资格失效，须重核。
 */
public class ColdChainLedger {

    private Long id;
    private Long reviewId;
    private String batchNo;
    /** 完整运输起点 UTC 精确时刻（首片段 start，登记时冻结）。 */
    private Instant transportStartUtc;
    /** 完整运输终点 UTC 精确时刻（末片段 end，登记时冻结）。 */
    private Instant transportEndUtc;
    /** 台账版本：登记为 1，每次成功补录/更正加 1。 */
    private int version;
    /** 温证合格冻结的台账版本；仅当等于 version 时温证当前有效，空值表示从未合格。 */
    private Integer qualifiedVersion;
    private Instant createdAt;
    private Instant updatedAt;

    /** 当前版本是否持有合格温证（冻结版本恰等于当前版本）。 */
    public boolean isCertificateValid() {
        return qualifiedVersion != null && qualifiedVersion == version;
    }

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

    public Instant getTransportStartUtc() {
        return transportStartUtc;
    }

    public void setTransportStartUtc(Instant transportStartUtc) {
        this.transportStartUtc = transportStartUtc;
    }

    public Instant getTransportEndUtc() {
        return transportEndUtc;
    }

    public void setTransportEndUtc(Instant transportEndUtc) {
        this.transportEndUtc = transportEndUtc;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Integer getQualifiedVersion() {
        return qualifiedVersion;
    }

    public void setQualifiedVersion(Integer qualifiedVersion) {
        this.qualifiedVersion = qualifiedVersion;
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
}
