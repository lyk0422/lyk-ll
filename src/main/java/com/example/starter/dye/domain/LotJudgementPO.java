package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 缸差判定台账 PO：同配方对照缸的出缸 ΔE 差值、处置结论与回修次数，可复算。
 */
public class LotJudgementPO {
    private Long id;
    private Long runId;
    private String runNo;
    private String recipeCode;
    private String baselineRunNo;
    private BigDecimal deltaEGap;
    private String result;
    private int repairCount;
    private String requestId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }
    public String getRecipeCode() { return recipeCode; }
    public void setRecipeCode(String recipeCode) { this.recipeCode = recipeCode; }
    public String getBaselineRunNo() { return baselineRunNo; }
    public void setBaselineRunNo(String baselineRunNo) { this.baselineRunNo = baselineRunNo; }
    public BigDecimal getDeltaEGap() { return deltaEGap; }
    public void setDeltaEGap(BigDecimal deltaEGap) { this.deltaEGap = deltaEGap; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public int getRepairCount() { return repairCount; }
    public void setRepairCount(int repairCount) { this.repairCount = repairCount; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
