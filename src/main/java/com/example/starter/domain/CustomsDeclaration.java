package com.example.starter.domain;

import java.time.Instant;

/**
 * 进口通关单台账 PO。一张合成通关单可关联多个包装子批共用核销，企业+品种固定不可改。
 */
public class CustomsDeclaration {

    private Long id;
    /** 合成通关单号（全局唯一）。 */
    private String customsNo;
    private String enterpriseName;
    private String productName;
    /** 有效截止精确时刻：签发要求 now &lt; 该时刻，恰等于即失效拒绝。 */
    private Instant validUntil;
    /** 可核销总支数（正整数，单位：支）。 */
    private int totalQuantity;
    /** 当前已预占未核销支数（单位：支）。 */
    private int reservedQuantity;
    /** 累计已核销支数（单位：支，只增不减）。 */
    private int writtenOffQuantity;
    private CustomsStatus status;
    /** 乐观版本号：登记为 1，每次成功变更加 1。 */
    private int version;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCustomsNo() {
        return customsNo;
    }

    public void setCustomsNo(String customsNo) {
        this.customsNo = customsNo;
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

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(int totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(int reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public int getWrittenOffQuantity() {
        return writtenOffQuantity;
    }

    public void setWrittenOffQuantity(int writtenOffQuantity) {
        this.writtenOffQuantity = writtenOffQuantity;
    }

    public CustomsStatus getStatus() {
        return status;
    }

    public void setStatus(CustomsStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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
