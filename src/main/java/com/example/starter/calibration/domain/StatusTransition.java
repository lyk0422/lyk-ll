package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 标定版本状态流转审计记录：每次状态转换保存操作者与时间。
 */
public class StatusTransition {

    /** 主键。 */
    private long id;
    /** 标定版本 ID。 */
    private long versionId;
    /** 转换前状态；创建时为 null。 */
    private CalibrationStatus fromStatus;
    /** 转换后状态。 */
    private CalibrationStatus toStatus;
    /** 操作者。 */
    private String operator;
    /** 转换时间（UTC）。 */
    private Instant operatedAt;
    /** 备注（如灰度批次 ID、回滚原因）。 */
    private String remark;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getVersionId() {
        return versionId;
    }

    public void setVersionId(long versionId) {
        this.versionId = versionId;
    }

    public CalibrationStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(CalibrationStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public CalibrationStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(CalibrationStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public Instant getOperatedAt() {
        return operatedAt;
    }

    public void setOperatedAt(Instant operatedAt) {
        this.operatedAt = operatedAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
