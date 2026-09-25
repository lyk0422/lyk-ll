package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 复工评估记录（一条处置链至多一条）。
 * status：APPLIED 已申请待双确认 / APPROVED 现场核查与监测复核均通过 / REJECTED 任一未通过。
 * siteConfirmed：现场核查；monitorConfirmed：监测复核；复工须两者同时为 1。
 */
public class ResumeAssessmentRecord {

    private Long id;
    private Long chainId;
    private String applyRequestId;
    private LocalDateTime applyTime;
    private LocalDateTime safeSince;
    private String status;
    private Boolean siteConfirmed;
    private Boolean monitorConfirmed;
    private String decideRequestId;
    private LocalDateTime decideTime;
    private LocalDateTime decidedAt;
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChainId() {
        return chainId;
    }

    public void setChainId(Long chainId) {
        this.chainId = chainId;
    }

    public String getApplyRequestId() {
        return applyRequestId;
    }

    public void setApplyRequestId(String applyRequestId) {
        this.applyRequestId = applyRequestId;
    }

    public LocalDateTime getApplyTime() {
        return applyTime;
    }

    public void setApplyTime(LocalDateTime applyTime) {
        this.applyTime = applyTime;
    }

    public LocalDateTime getSafeSince() {
        return safeSince;
    }

    public void setSafeSince(LocalDateTime safeSince) {
        this.safeSince = safeSince;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getSiteConfirmed() {
        return siteConfirmed;
    }

    public void setSiteConfirmed(Boolean siteConfirmed) {
        this.siteConfirmed = siteConfirmed;
    }

    public Boolean getMonitorConfirmed() {
        return monitorConfirmed;
    }

    public void setMonitorConfirmed(Boolean monitorConfirmed) {
        this.monitorConfirmed = monitorConfirmed;
    }

    public String getDecideRequestId() {
        return decideRequestId;
    }

    public void setDecideRequestId(String decideRequestId) {
        this.decideRequestId = decideRequestId;
    }

    public LocalDateTime getDecideTime() {
        return decideTime;
    }

    public void setDecideTime(LocalDateTime decideTime) {
        this.decideTime = decideTime;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
