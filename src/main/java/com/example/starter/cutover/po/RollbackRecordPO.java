package com.example.starter.cutover.po;

import java.time.LocalDateTime;

/**
 * 回退记录持久化对象，对应表 rollback_record，每个割接最多一条。
 */
public class RollbackRecordPO {

    private Long id;
    private Long cutoverId;
    /** 回退判据枚举名。 */
    private String triggerReason;
    private String detail;
    private Long stepId;
    /** 回退时点步骤状态快照（JSON）。 */
    private String snapshotJson;
    private LocalDateTime rolledBackAt;

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

    public String getTriggerReason() {
        return triggerReason;
    }

    public void setTriggerReason(String triggerReason) {
        this.triggerReason = triggerReason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public LocalDateTime getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(LocalDateTime rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }
}
