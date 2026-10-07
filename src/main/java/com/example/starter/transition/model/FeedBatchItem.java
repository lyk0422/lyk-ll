package com.example.starter.transition.model;

import java.math.BigDecimal;

/**
 * 投料明细：每卷扣量凭据；卷改判正品后已消费凭据保留可查。
 */
public class FeedBatchItem {

    private Long id;
    private String feedNo;
    private String rollNo;
    /** 本卷投料公斤数，单位 kg，正数最多三位小数。 */
    private BigDecimal weightKg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFeedNo() {
        return feedNo;
    }

    public void setFeedNo(String feedNo) {
        this.feedNo = feedNo;
    }

    public String getRollNo() {
        return rollNo;
    }

    public void setRollNo(String rollNo) {
        this.rollNo = rollNo;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }
}
