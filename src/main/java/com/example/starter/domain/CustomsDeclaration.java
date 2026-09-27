package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 进口疫苗合成通关单 PO。一张通关单可关联多个包装子批，企业、品种、可核销总数固定；
 * 预占累计不得超过总数，恰占满合法；可撤销，撤销后阻断签发并释放全部未签发预占。
 */
public class CustomsDeclaration {

    private Long id;
    private String declarationNo;
    private String enterpriseName;
    private String productName;
    /** 有效截止日期（UTC 日界判定，now 恰为截止日即拒绝签发）。 */
    private LocalDate validUntil;
    /** 可核销总数量（正整数，单位：支）。 */
    private Integer totalQuantity;
    /** 乐观锁版本，从 1 开始，每次成功变更 +1。 */
    private Integer version;
    /** 是否已撤销：撤销后阻断全部未签发子批签发。 */
    private boolean revoked;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeclarationNo() {
        return declarationNo;
    }

    public void setDeclarationNo(String declarationNo) {
        this.declarationNo = declarationNo;
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

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate validUntil) {
        this.validUntil = validUntil;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
