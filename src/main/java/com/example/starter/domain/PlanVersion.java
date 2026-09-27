package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前计划版本。发布即不可变：版本号、96 段明细与指纹均不随后续发布改变。
 * currentFlag 为 "Y" 时是同风场同 UTC 日期的唯一当前版本；历史版本为 null。
 */
public class PlanVersion {

    private Long id;
    private String farmCode;
    private LocalDate planDate;
    private int versionNo;
    private String currentFlag;
    private String requestId;
    private String fingerprint;
    private Instant publishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFarmCode() {
        return farmCode;
    }

    public void setFarmCode(String farmCode) {
        this.farmCode = farmCode;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public void setPlanDate(LocalDate planDate) {
        this.planDate = planDate;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public String getCurrentFlag() {
        return currentFlag;
    }

    public void setCurrentFlag(String currentFlag) {
        this.currentFlag = currentFlag;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
