package com.example.starter.domain;

import java.time.Instant;

/**
 * 企业-品种维度受理状态 PO。连续 3 批不合格触发暂停受理并转核查。
 */
public class EnterpriseProductStatus {

    private Long id;
    private String enterpriseName;
    private String productName;
    /** 连续不合格批次数；任一合格批评定后清零，恰为 3 触发暂停。 */
    private int consecutiveFailed;
    private boolean suspended;
    private Instant suspendedAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEnterpriseName() {
        return enterpriseName;
    }

    public void setEnterpriseName(String enterpriseName) {
        this.enterpriseName = enterpriseName;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public int getConsecutiveFailed() {
        return consecutiveFailed;
    }

    public void setConsecutiveFailed(int consecutiveFailed) {
        this.consecutiveFailed = consecutiveFailed;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    public Instant getSuspendedAt() {
        return suspendedAt;
    }

    public void setSuspendedAt(Instant suspendedAt) {
        this.suspendedAt = suspendedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
