package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 染化料主数据与库存 PO。库存单位：克。
 */
public class DyestuffPO {
    private Long id;
    private String code;
    private String name;
    private BigDecimal stockGrams;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getStockGrams() { return stockGrams; }
    public void setStockGrams(BigDecimal stockGrams) { this.stockGrams = stockGrams; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
