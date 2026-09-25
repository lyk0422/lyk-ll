package com.example.starter.legality.po;

import java.time.Instant;

/**
 * 机组成员值勤记录持久化行。
 */
public class DutyRecordPo {

    private Long id;
    private Long batchId;
    private String requestId;
    private String memberCode;
    private String flightNo;
    private Instant reportTimeUtc;
    private Instant plannedEndUtc;
    private int tierLimitMinutes;
    private int totalExtensionMinutes;
    private Instant dutyEndUtc;
    private String result;
    private String reasons;
    private boolean accepted;
    private String fingerprint;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getMemberCode() {
        return memberCode;
    }

    public void setMemberCode(String memberCode) {
        this.memberCode = memberCode;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public void setFlightNo(String flightNo) {
        this.flightNo = flightNo;
    }

    public Instant getReportTimeUtc() {
        return reportTimeUtc;
    }

    public void setReportTimeUtc(Instant reportTimeUtc) {
        this.reportTimeUtc = reportTimeUtc;
    }

    public Instant getPlannedEndUtc() {
        return plannedEndUtc;
    }

    public void setPlannedEndUtc(Instant plannedEndUtc) {
        this.plannedEndUtc = plannedEndUtc;
    }

    public int getTierLimitMinutes() {
        return tierLimitMinutes;
    }

    public void setTierLimitMinutes(int tierLimitMinutes) {
        this.tierLimitMinutes = tierLimitMinutes;
    }

    public int getTotalExtensionMinutes() {
        return totalExtensionMinutes;
    }

    public void setTotalExtensionMinutes(int totalExtensionMinutes) {
        this.totalExtensionMinutes = totalExtensionMinutes;
    }

    public Instant getDutyEndUtc() {
        return dutyEndUtc;
    }

    public void setDutyEndUtc(Instant dutyEndUtc) {
        this.dutyEndUtc = dutyEndUtc;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getReasons() {
        return reasons;
    }

    public void setReasons(String reasons) {
        this.reasons = reasons;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
