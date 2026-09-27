package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 日前发电计划版本：发布后不可变。
 * version 同风场同 UTC 日期从 1 开始，每次成功发布加 1；
 * current 仅当前版本为 true，历史版本保留但不再允许被新指令绑定。
 */
public class DayAheadPlan {

    private Long id;
    private String farmCode;
    private LocalDate planDate;
    private int version;
    private String requestId;
    private String fingerprint;
    private boolean current;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
