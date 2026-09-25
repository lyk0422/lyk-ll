package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 缸次 PO：一次染色执行。重量单位千克织物；outDeltaE 空值表示尚未测色。
 */
public class VatRunPO {
    private Long id;
    private String runNo;
    private Long vatId;
    private Long recipeId;
    private String recipeCode;
    private int recipeVersion;
    private int liquorRatio;
    private BigDecimal fabricKg;
    private String mergedRunNo;
    private RunStatus status;
    private int repairCount;
    private BigDecimal outDeltaE;
    private Disposition finalDisposition;
    private LocalDateTime judgedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunNo() { return runNo; }
    public void setRunNo(String runNo) { this.runNo = runNo; }
    public Long getVatId() { return vatId; }
    public void setVatId(Long vatId) { this.vatId = vatId; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public String getRecipeCode() { return recipeCode; }
    public void setRecipeCode(String recipeCode) { this.recipeCode = recipeCode; }
    public int getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(int recipeVersion) { this.recipeVersion = recipeVersion; }
    public int getLiquorRatio() { return liquorRatio; }
    public void setLiquorRatio(int liquorRatio) { this.liquorRatio = liquorRatio; }
    public BigDecimal getFabricKg() { return fabricKg; }
    public void setFabricKg(BigDecimal fabricKg) { this.fabricKg = fabricKg; }
    public String getMergedRunNo() { return mergedRunNo; }
    public void setMergedRunNo(String mergedRunNo) { this.mergedRunNo = mergedRunNo; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public int getRepairCount() { return repairCount; }
    public void setRepairCount(int repairCount) { this.repairCount = repairCount; }
    public BigDecimal getOutDeltaE() { return outDeltaE; }
    public void setOutDeltaE(BigDecimal outDeltaE) { this.outDeltaE = outDeltaE; }
    public Disposition getFinalDisposition() { return finalDisposition; }
    public void setFinalDisposition(Disposition finalDisposition) { this.finalDisposition = finalDisposition; }
    public LocalDateTime getJudgedAt() { return judgedAt; }
    public void setJudgedAt(LocalDateTime judgedAt) { this.judgedAt = judgedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
