package com.example.starter.dye.domain;

import java.math.BigDecimal;

/**
 * 染缸设备 PO。activeRunId 非空表示被某执行中缸次占用（并发排缸唯一受理依赖条件更新）。
 */
public class DyeVatPO {
    private Long id;
    private String vatCode;
    private BigDecimal capacityKg;
    private Long activeRunId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVatCode() { return vatCode; }
    public void setVatCode(String vatCode) { this.vatCode = vatCode; }
    public BigDecimal getCapacityKg() { return capacityKg; }
    public void setCapacityKg(BigDecimal capacityKg) { this.capacityKg = capacityKg; }
    public Long getActiveRunId() { return activeRunId; }
    public void setActiveRunId(Long activeRunId) { this.activeRunId = activeRunId; }
}
