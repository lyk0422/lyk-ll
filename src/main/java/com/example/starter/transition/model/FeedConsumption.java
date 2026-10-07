package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投料消耗明细：每卷扣量凭据；复议改判正品使额度失效后，已消费凭据仍保留。
 */
public class FeedConsumption {

    private Long id;
    private String feedNo;
    private String rollNo;
    /** 本卷消费公斤数，单位 kg。 */
    private BigDecimal kg;
    private LocalDateTime createdAt;

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

    public BigDecimal getKg() {
        return kg;
    }

    public void setKg(BigDecimal kg) {
        this.kg = kg;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
