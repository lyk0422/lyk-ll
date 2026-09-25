package com.example.starter.po;

import com.example.starter.domain.ValveAction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 阀门开度版本 PO（表 valve_opening_version）。每次步进一条，形成版本链。
 */
public class ValveOpeningVersion {
    private Long id;
    /** 阀门ID */
    private Long valveId;
    /** 同一阀门内递增版本号，从 1 开始 */
    private Integer versionNo;
    /** 步进前开度，单位:% */
    private BigDecimal fromOpening;
    /** 步进后开度，单位:% */
    private BigDecimal toOpening;
    /** 本次步进幅度 0~10，单位:% */
    private BigDecimal delta;
    /** 调节方向 OPEN/CLOSE */
    private ValveAction action;
    /** 所属调节任务ID */
    private Long taskId;
    /** 触发该版本的调节令幂等键 */
    private String requestId;
    /** 变更时刻(UTC) */
    private Instant changedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getValveId() { return valveId; }
    public void setValveId(Long valveId) { this.valveId = valveId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public BigDecimal getFromOpening() { return fromOpening; }
    public void setFromOpening(BigDecimal fromOpening) { this.fromOpening = fromOpening; }
    public BigDecimal getToOpening() { return toOpening; }
    public void setToOpening(BigDecimal toOpening) { this.toOpening = toOpening; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal delta) { this.delta = delta; }
    public ValveAction getAction() { return action; }
    public void setAction(ValveAction action) { this.action = action; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
}
