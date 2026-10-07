package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投料申请单头：一次投料引用同一执行中订单、当前掺配版本与总浆量；
 * 回收合计须精确等于总浆量乘该版本目标比例。
 */
public class FeedApplication {

    private Long id;
    private String feedNo;
    private String orderNo;
    /** 投料时生效的掺配比例版本号。 */
    private int blendVersionNo;
    /** 总浆量，单位 kg。 */
    private BigDecimal totalPulpKg;
    /** 应投回收量=总浆量×目标比例/100，单位 kg，精确计算。 */
    private BigDecimal requiredRecycleKg;
    /** 实际回收合计=各卷公斤数之和，单位 kg。 */
    private BigDecimal actualRecycleKg;
    private String requestId;
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

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public int getBlendVersionNo() {
        return blendVersionNo;
    }

    public void setBlendVersionNo(int blendVersionNo) {
        this.blendVersionNo = blendVersionNo;
    }

    public BigDecimal getTotalPulpKg() {
        return totalPulpKg;
    }

    public void setTotalPulpKg(BigDecimal totalPulpKg) {
        this.totalPulpKg = totalPulpKg;
    }

    public BigDecimal getRequiredRecycleKg() {
        return requiredRecycleKg;
    }

    public void setRequiredRecycleKg(BigDecimal requiredRecycleKg) {
        this.requiredRecycleKg = requiredRecycleKg;
    }

    public BigDecimal getActualRecycleKg() {
        return actualRecycleKg;
    }

    public void setActualRecycleKg(BigDecimal actualRecycleKg) {
        this.actualRecycleKg = actualRecycleKg;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
