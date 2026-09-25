package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 处置链：监测→预警分级→处置→撤人→复工评估→归档。
 * activeKey 为测区编码时表示执行中（全表唯一），归档后置 NULL 释放测区槽位。
 */
public class DisposalChain {

    private Long id;
    private String chainNo;
    private String zoneCode;
    private String requestId;
    private String alarmLevel;
    private LocalDateTime triggerTime;
    private String status;
    private String activeKey;
    private String evacStatus;
    private LocalDateTime evacConfirmTime;
    private String resumeStatus;
    private LocalDateTime resumeApplyTime;
    private LocalDateTime resumeDecideTime;
    private LocalDateTime archiveTime;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChainNo() {
        return chainNo;
    }

    public void setChainNo(String chainNo) {
        this.chainNo = chainNo;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getAlarmLevel() {
        return alarmLevel;
    }

    public void setAlarmLevel(String alarmLevel) {
        this.alarmLevel = alarmLevel;
    }

    public LocalDateTime getTriggerTime() {
        return triggerTime;
    }

    public void setTriggerTime(LocalDateTime triggerTime) {
        this.triggerTime = triggerTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getActiveKey() {
        return activeKey;
    }

    public void setActiveKey(String activeKey) {
        this.activeKey = activeKey;
    }

    public String getEvacStatus() {
        return evacStatus;
    }

    public void setEvacStatus(String evacStatus) {
        this.evacStatus = evacStatus;
    }

    public LocalDateTime getEvacConfirmTime() {
        return evacConfirmTime;
    }

    public void setEvacConfirmTime(LocalDateTime evacConfirmTime) {
        this.evacConfirmTime = evacConfirmTime;
    }

    public String getResumeStatus() {
        return resumeStatus;
    }

    public void setResumeStatus(String resumeStatus) {
        this.resumeStatus = resumeStatus;
    }

    public LocalDateTime getResumeApplyTime() {
        return resumeApplyTime;
    }

    public void setResumeApplyTime(LocalDateTime resumeApplyTime) {
        this.resumeApplyTime = resumeApplyTime;
    }

    public LocalDateTime getResumeDecideTime() {
        return resumeDecideTime;
    }

    public void setResumeDecideTime(LocalDateTime resumeDecideTime) {
        this.resumeDecideTime = resumeDecideTime;
    }

    public LocalDateTime getArchiveTime() {
        return archiveTime;
    }

    public void setArchiveTime(LocalDateTime archiveTime) {
        this.archiveTime = archiveTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
