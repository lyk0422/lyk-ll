package com.example.starter.dye.domain;

import java.math.BigDecimal;

/**
 * 配方条目 PO：小样浓度，单位克/升。
 */
public class RecipeItemPO {
    private Long id;
    private Long recipeId;
    private String dyestuffCode;
    private BigDecimal gramsPerLiter;
    private int seqNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public String getDyestuffCode() { return dyestuffCode; }
    public void setDyestuffCode(String dyestuffCode) { this.dyestuffCode = dyestuffCode; }
    public BigDecimal getGramsPerLiter() { return gramsPerLiter; }
    public void setGramsPerLiter(BigDecimal gramsPerLiter) { this.gramsPerLiter = gramsPerLiter; }
    public int getSeqNo() { return seqNo; }
    public void setSeqNo(int seqNo) { this.seqNo = seqNo; }
}
