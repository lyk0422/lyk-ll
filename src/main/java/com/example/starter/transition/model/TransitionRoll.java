package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 过渡纸卷：按卷号唯一登记，强制降级，不得按正品入库；
 * 检验恰达正品门槛时最多复议改判一次。
 */
public class TransitionRoll {

    private Long id;
    private String rollNo;
    private String orderNo;
    private String paperMachine;
    /** 纸卷长度，单位 m，可空。 */
    private BigDecimal lengthM;
    /** 判定终态：DOWNGRADED / UPGRADED。 */
    private String verdict;
    /** 入库状态：NON_GENUINE / GENUINE。 */
    private String warehouseStatus;
    /** 是否具备复议资格（首次检验达正品门槛）。 */
    private boolean eligibleReexam;
    /** 已受理复议次数，仅允许 0 或 1。 */
    private int reexamCount;
    private LocalDateTime registeredAt;
    private LocalDateTime verdictChangedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRollNo() {
        return rollNo;
    }

    public void setRollNo(String rollNo) {
        this.rollNo = rollNo;
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

    public BigDecimal getLengthM() {
        return lengthM;
    }

    public void setLengthM(BigDecimal lengthM) {
        this.lengthM = lengthM;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getWarehouseStatus() {
        return warehouseStatus;
    }

    public void setWarehouseStatus(String warehouseStatus) {
        this.warehouseStatus = warehouseStatus;
    }

    public boolean isEligibleReexam() {
        return eligibleReexam;
    }

    public void setEligibleReexam(boolean eligibleReexam) {
        this.eligibleReexam = eligibleReexam;
    }

    public int getReexamCount() {
        return reexamCount;
    }

    public void setReexamCount(int reexamCount) {
        this.reexamCount = reexamCount;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public LocalDateTime getVerdictChangedAt() {
        return verdictChangedAt;
    }

    public void setVerdictChangedAt(LocalDateTime verdictChangedAt) {
        this.verdictChangedAt = verdictChangedAt;
    }
}
