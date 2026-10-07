package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投料申请单：一次投料引用同一执行中订单、当前掺配版本与总浆量；
 * 回收合计须精确等于 总浆量×目标比例/100。
 */
public class FeedBatch {

    private Long id;
    private String feedNo;
    private String orderNo;
    /** 投料时生效的掺配比例版本号。 */
    private int versionNo;
    /** 总浆量，单位 kg，最多三位小数。 */
    private BigDecimal totalPulpKg;
    /** 投料时版本目标掺配比例快照，单位 %。 */
    private BigDecimal targetRatioPct;
    /** 应投回收量=总浆量×目标比例/100，单位 kg。 */
    private BigDecimal requiredKg;
    /** 实际回收合计，单位 kg，精确等于应投回收量。 */
    private BigDecimal actualKg;
    /** 回收卷数量，1 至 10 个不同卷。 */
    private int rollCount;
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

    public int getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(int versionNo) {
        this.versionNo = versionNo;
    }

    public BigDecimal getTotalPulpKg() {
        return totalPulpKg;
    }

    public void setTotalPulpKg(BigDecimal totalPulpKg) {
        this.totalPulpKg = totalPulpKg;
    }

    public BigDecimal getTargetRatioPct() {
        return targetRatioPct;
    }

    public void setTargetRatioPct(BigDecimal targetRatioPct) {
        this.targetRatioPct = targetRatioPct;
    }

    public BigDecimal getRequiredKg() {
        return requiredKg;
    }

    public void setRequiredKg(BigDecimal requiredKg) {
        this.requiredKg = requiredKg;
    }

    public BigDecimal getActualKg() {
        return actualKg;
    }

    public void setActualKg(BigDecimal actualKg) {
        this.actualKg = actualKg;
    }

    public int getRollCount() {
        return rollCount;
    }

    public void setRollCount(int rollCount) {
        this.rollCount = rollCount;
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
