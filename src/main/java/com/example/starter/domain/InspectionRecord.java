package com.example.starter.domain;

/**
 * 检验项目明细 PO（检验链，可逐项复算）。
 */
public class InspectionRecord {

    private Long id;
    private Long reviewId;
    private String itemCode;
    private String itemName;
    /** 效力/无菌/异常毒性为关键项目。 */
    private boolean critical;
    /** 实测值文本（数值或“无菌/有菌”）。 */
    private String measuredValue;
    /** 限值文本（含等号边界）。 */
    private String limitValue;
    /** 判定方式 GTE/LTE/EQ/RANGE。 */
    private String comparison;
    /** RANGE 判定上限（含边界），其余方式为空。 */
    private String limitMax;
    private boolean qualified;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public boolean isCritical() {
        return critical;
    }

    public void setCritical(boolean critical) {
        this.critical = critical;
    }

    public String getMeasuredValue() {
        return measuredValue;
    }

    public void setMeasuredValue(String measuredValue) {
        this.measuredValue = measuredValue;
    }

    public String getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(String limitValue) {
        this.limitValue = limitValue;
    }

    public String getComparison() {
        return comparison;
    }

    public void setComparison(String comparison) {
        this.comparison = comparison;
    }

    public String getLimitMax() {
        return limitMax;
    }

    public void setLimitMax(String limitMax) {
        this.limitMax = limitMax;
    }

    public boolean isQualified() {
        return qualified;
    }

    public void setQualified(boolean qualified) {
        this.qualified = qualified;
    }
}
