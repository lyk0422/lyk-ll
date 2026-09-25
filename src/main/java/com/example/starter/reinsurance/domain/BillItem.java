package com.example.starter.reinsurance.domain;

import java.math.BigDecimal;

/**
 * 账单明细行对象；可逐笔复算，全部明细合计必须等于账单汇总。
 */
public class BillItem {

    private Long id;
    private Long billId;
    private Integer lineNo;
    private BillItemType itemType;
    private String refNo;
    private String refBillNo;
    private BigDecimal grossAmount;
    private BigDecimal cessionRate;
    private BigDecimal amount;
    private String remark;

    public BillItem() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBillId() {
        return billId;
    }

    public void setBillId(Long billId) {
        this.billId = billId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public BillItemType getItemType() {
        return itemType;
    }

    public void setItemType(BillItemType itemType) {
        this.itemType = itemType;
    }

    public String getRefNo() {
        return refNo;
    }

    public void setRefNo(String refNo) {
        this.refNo = refNo;
    }

    public String getRefBillNo() {
        return refBillNo;
    }

    public void setRefBillNo(String refBillNo) {
        this.refBillNo = refBillNo;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public void setGrossAmount(BigDecimal grossAmount) {
        this.grossAmount = grossAmount;
    }

    public BigDecimal getCessionRate() {
        return cessionRate;
    }

    public void setCessionRate(BigDecimal cessionRate) {
        this.cessionRate = cessionRate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
