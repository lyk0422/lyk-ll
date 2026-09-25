package com.example.starter.cutover.po;

import java.time.LocalDateTime;

/**
 * 迟到步骤回报事件持久化对象，对应表 step_report_event。
 */
public class StepReportEventPO {

    private Long id;
    private Long cutoverId;
    private Integer stepSeq;
    private String requestId;
    /** 回报结果枚举名：SUCCESS/FAIL/HARD_FAIL。 */
    private String result;
    private String note;
    private LocalDateTime reportedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCutoverId() {
        return cutoverId;
    }

    public void setCutoverId(Long cutoverId) {
        this.cutoverId = cutoverId;
    }

    public Integer getStepSeq() {
        return stepSeq;
    }

    public void setStepSeq(Integer stepSeq) {
        this.stepSeq = stepSeq;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }
}
