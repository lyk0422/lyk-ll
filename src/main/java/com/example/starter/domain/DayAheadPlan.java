package com.example.starter.domain;

import java.time.Instant;

/**
 * 日前发电计划版本头。
 * planDate 计划 UTC 日期（yyyy-MM-dd）；versionNo 同风场同日期从 1 单调递增；
 * currentFlag 仅当前版本为 "Y"，历史版本为 null；版本一经发布不可变。
 */
public class DayAheadPlan {

    private Long id;
    private String farmCode;
    private String planDate;
    private int versionNo;
    private String currentFlag;
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

    public String getPlanDate() {
        return planDate;
    }

    public void setPlanDate(String planDate) {
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
