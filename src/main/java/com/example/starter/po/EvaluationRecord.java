package com.example.starter.po;

import com.example.starter.domain.EvaluationPhase;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 评估记录 PO（表 evaluation_record），失衡判定与效果评估均落表，支持前后对比复算。
 */
public class EvaluationRecord {
    private Long id;
    /** 所属调节任务ID */
    private Long taskId;
    /** 换热站ID */
    private Long stationId;
    /** 评估幂等键 */
    private String requestId;
    /** 评估阶段 JUDGMENT/RE_EVAL */
    private EvaluationPhase phase;
    /** 最高回水温度，单位:℃ */
    private BigDecimal maxReturnTemp;
    /** 最低回水温度，单位:℃ */
    private BigDecimal minReturnTemp;
    /** 回水温度极差=最高-最低，单位:℃ */
    private BigDecimal tempDiff;
    /** 温差<=2℃为平衡（恰 2℃合格） */
    private Boolean balanced;
    /** 各单元温差与欠流/过流分类明细 JSON */
    private String detailJson;
    /** 评估时刻(UTC) */
    private Instant evaluatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public EvaluationPhase getPhase() { return phase; }
    public void setPhase(EvaluationPhase phase) { this.phase = phase; }
    public BigDecimal getMaxReturnTemp() { return maxReturnTemp; }
    public void setMaxReturnTemp(BigDecimal maxReturnTemp) { this.maxReturnTemp = maxReturnTemp; }
    public BigDecimal getMinReturnTemp() { return minReturnTemp; }
    public void setMinReturnTemp(BigDecimal minReturnTemp) { this.minReturnTemp = minReturnTemp; }
    public BigDecimal getTempDiff() { return tempDiff; }
    public void setTempDiff(BigDecimal tempDiff) { this.tempDiff = tempDiff; }
    public Boolean getBalanced() { return balanced; }
    public void setBalanced(Boolean balanced) { this.balanced = balanced; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
