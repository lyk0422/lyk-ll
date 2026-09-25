package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 改品种单：申请→方式判定→执行→过渡料处置→完成→归档。
 */
public class TransitionOrder {

    private Long id;
    private String orderNo;
    private String paperMachine;
    private String fromGrade;
    private String toGrade;
    private BigDecimal basisWeightFrom;
    private BigDecimal basisWeightTo;
    private int ashLevelFrom;
    private int ashLevelTo;
    /** 克重差百分比，单位 %；未判定时为 null。 */
    private BigDecimal basisWeightDeltaPct;
    /** 灰分等级差绝对值（级）；未判定时为 null。 */
    private Integer ashLevelDelta;
    /** 是否直切：true=直切，false=停机清洗；未判定为 null。 */
    private Boolean directSwitch;
    /** 是否冲洗网部；未判定为 null。 */
    private Boolean wireFlush;
    /** 切换时间，单位 秒。 */
    private Integer switchTimeSec;
    /** 执行车速，单位 m/min。 */
    private BigDecimal machineSpeedMMin;
    /** 执行上料浓度，单位 %。 */
    private BigDecimal stockConsistencyPct;
    /** 过渡料长度 = 车速(m/min) * 切换时间(秒) / 60，单位 m。 */
    private BigDecimal transitionLengthM;
    private String status;
    private String appliedRequestId;
    private LocalDateTime appliedAt;
    private LocalDateTime decidedAt;
    private LocalDateTime executedAt;
    private LocalDateTime disposedAt;
    private LocalDateTime completedAt;
    private LocalDateTime archivedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getPaperMachine() {
        return paperMachine;
    }

    public void setPaperMachine(String paperMachine) {
        this.paperMachine = paperMachine;
    }

    public String getFromGrade() {
        return fromGrade;
    }

    public void setFromGrade(String fromGrade) {
        this.fromGrade = fromGrade;
    }

    public String getToGrade() {
        return toGrade;
    }

    public void setToGrade(String toGrade) {
        this.toGrade = toGrade;
    }

    public BigDecimal getBasisWeightFrom() {
        return basisWeightFrom;
    }

    public void setBasisWeightFrom(BigDecimal basisWeightFrom) {
        this.basisWeightFrom = basisWeightFrom;
    }

    public BigDecimal getBasisWeightTo() {
        return basisWeightTo;
    }

    public void setBasisWeightTo(BigDecimal basisWeightTo) {
        this.basisWeightTo = basisWeightTo;
    }

    public int getAshLevelFrom() {
        return ashLevelFrom;
    }

    public void setAshLevelFrom(int ashLevelFrom) {
        this.ashLevelFrom = ashLevelFrom;
    }

    public int getAshLevelTo() {
        return ashLevelTo;
    }

    public void setAshLevelTo(int ashLevelTo) {
        this.ashLevelTo = ashLevelTo;
    }

    public BigDecimal getBasisWeightDeltaPct() {
        return basisWeightDeltaPct;
    }

    public void setBasisWeightDeltaPct(BigDecimal basisWeightDeltaPct) {
        this.basisWeightDeltaPct = basisWeightDeltaPct;
    }

    public Integer getAshLevelDelta() {
        return ashLevelDelta;
    }

    public void setAshLevelDelta(Integer ashLevelDelta) {
        this.ashLevelDelta = ashLevelDelta;
    }

    public Boolean getDirectSwitch() {
        return directSwitch;
    }

    public void setDirectSwitch(Boolean directSwitch) {
        this.directSwitch = directSwitch;
    }

    public Boolean getWireFlush() {
        return wireFlush;
    }

    public void setWireFlush(Boolean wireFlush) {
        this.wireFlush = wireFlush;
    }

    public Integer getSwitchTimeSec() {
        return switchTimeSec;
    }

    public void setSwitchTimeSec(Integer switchTimeSec) {
        this.switchTimeSec = switchTimeSec;
    }

    public BigDecimal getMachineSpeedMMin() {
        return machineSpeedMMin;
    }

    public void setMachineSpeedMMin(BigDecimal machineSpeedMMin) {
        this.machineSpeedMMin = machineSpeedMMin;
    }

    public BigDecimal getStockConsistencyPct() {
        return stockConsistencyPct;
    }

    public void setStockConsistencyPct(BigDecimal stockConsistencyPct) {
        this.stockConsistencyPct = stockConsistencyPct;
    }

    public BigDecimal getTransitionLengthM() {
        return transitionLengthM;
    }

    public void setTransitionLengthM(BigDecimal transitionLengthM) {
        this.transitionLengthM = transitionLengthM;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAppliedRequestId() {
        return appliedRequestId;
    }

    public void setAppliedRequestId(String appliedRequestId) {
        this.appliedRequestId = appliedRequestId;
    }

    public LocalDateTime getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(LocalDateTime appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public LocalDateTime getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }

    public LocalDateTime getDisposedAt() {
        return disposedAt;
    }

    public void setDisposedAt(LocalDateTime disposedAt) {
        this.disposedAt = disposedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }
}
