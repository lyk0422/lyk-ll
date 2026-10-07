package com.example.starter.domain;

import java.time.Instant;

/**
 * 分钟确认记录：整分钟全部参与机组一起确认后登记；缺一机组零更新（无记录）。
 */
public class StagedMinuteConfirm {

    private Long id;
    private Long planId;
    private int planVersion;
    private int minuteNo;
    private String requestId;
    private Instant confirmedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(int planVersion) {
        this.planVersion = planVersion;
    }

    public int getMinuteNo() {
        return minuteNo;
    }

    public void setMinuteNo(int minuteNo) {
        this.minuteNo = minuteNo;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
