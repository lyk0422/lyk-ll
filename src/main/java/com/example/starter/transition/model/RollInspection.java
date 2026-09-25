package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 纸卷检验记录：INITIAL 首次检验、REEXAM 复议检验各至多一条。
 */
public class RollInspection {

    private Long id;
    private String rollNo;
    /** 检验类型：INITIAL / REEXAM。 */
    private String inspectionType;
    private String requestId;
    /** 灰分实测值，单位 %。 */
    private BigDecimal ashValuePct;
    /** 水分实测值，单位 %。 */
    private BigDecimal moisturePct;
    /** 抗张指数合成指标，单位 Nm/g。 */
    private BigDecimal tensileIndex;
    /** 定量实测值，单位 g/m2。 */
    private BigDecimal basisWeightGM2;
    /** 是否恰达/超过正品门槛。 */
    private boolean meetsThreshold;
    /** GENUINE_ELIGIBLE / BELOW_THRESHOLD / REEXAM_UPGRADED / REEXAM_REJECTED。 */
    private String verdict;
    private LocalDateTime createdAt;

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

    public String getInspectionType() {
        return inspectionType;
    }

    public void setInspectionType(String inspectionType) {
        this.inspectionType = inspectionType;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public BigDecimal getAshValuePct() {
        return ashValuePct;
    }

    public void setAshValuePct(BigDecimal ashValuePct) {
        this.ashValuePct = ashValuePct;
    }

    public BigDecimal getMoisturePct() {
        return moisturePct;
    }

    public void setMoisturePct(BigDecimal moisturePct) {
        this.moisturePct = moisturePct;
    }

    public BigDecimal getTensileIndex() {
        return tensileIndex;
    }

    public void setTensileIndex(BigDecimal tensileIndex) {
        this.tensileIndex = tensileIndex;
    }

    public BigDecimal getBasisWeightGM2() {
        return basisWeightGM2;
    }

    public void setBasisWeightGM2(BigDecimal basisWeightGM2) {
        this.basisWeightGM2 = basisWeightGM2;
    }

    public boolean isMeetsThreshold() {
        return meetsThreshold;
    }

    public void setMeetsThreshold(boolean meetsThreshold) {
        this.meetsThreshold = meetsThreshold;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
