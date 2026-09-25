package com.example.starter.po;

import com.example.starter.domain.ValveAction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单元调节动作台账 PO（表 adjustment_record），用于 24 小时(UTC)调节次数统计。
 */
public class AdjustmentRecord {
    private Long id;
    /** 所属调节任务ID */
    private Long taskId;
    /** 被调节单元ID */
    private Long unitId;
    /** 被调节阀门ID */
    private Long valveId;
    /** 调节令幂等键 */
    private String requestId;
    /** 同一调节令内的步进序号，从 1 开始 */
    private Integer stepIndex;
    /** 调节方向 OPEN/CLOSE */
    private ValveAction action;
    /** 步进前开度，单位:% */
    private BigDecimal fromOpening;
    /** 步进后开度，单位:% */
    private BigDecimal toOpening;
    /** 步进幅度，单位:% */
    private BigDecimal delta;
    /** 调节时刻(UTC) */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long unitId) { this.unitId = unitId; }
    public Long getValveId() { return valveId; }
    public void setValveId(Long valveId) { this.valveId = valveId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Integer getStepIndex() { return stepIndex; }
    public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }
    public ValveAction getAction() { return action; }
    public void setAction(ValveAction action) { this.action = action; }
    public BigDecimal getFromOpening() { return fromOpening; }
    public void setFromOpening(BigDecimal fromOpening) { this.fromOpening = fromOpening; }
    public BigDecimal getToOpening() { return toOpening; }
    public void setToOpening(BigDecimal toOpening) { this.toOpening = toOpening; }
    public BigDecimal getDelta() { return delta; }
    public void setDelta(BigDecimal delta) { this.delta = delta; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
