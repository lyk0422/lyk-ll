package com.example.starter.domain;

import java.time.Instant;

/**
 * 进口通关单 PO。版本从 1 开始，每次成功变更加 1。
 */
public class CustomsDeclaration {

    private Long id;
    private String declarationNo;
    private String enterpriseName;
    private String productName;
    /** 通关有效截止时刻：业务时刻 now<截止 有效，恰截止拒绝。 */
    private Instant validUntil;
    /** 可核销总支数（正整数，修订仅允许调增）。 */
    private int totalDoses;
    /** 当前预占支数。 */
    private int reservedDoses;
    /** 已核销支数。 */
    private int consumedDoses;
    private CustomsDeclarationStatus status;
    /** 乐观锁版本，从 1 开始。 */
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    /** 未占余量 = 总支数 - 预占 - 已核销。 */
    public int availableDoses() {
        return totalDoses - reservedDoses - consumedDoses;
    }

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

    public Instant getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(Instant validUntil) {
        this.validUntil = validUntil;
    }

    public int getTotalDoses() {
        return totalDoses;
    }

    public void setTotalDoses(int totalDoses) {
        this.totalDoses = totalDoses;
    }

    public int getReservedDoses() {
        return reservedDoses;
    }

    public void setReservedDoses(int reservedDoses) {
        this.reservedDoses = reservedDoses;
    }

    public int getConsumedDoses() {
        return consumedDoses;
    }

    public void setConsumedDoses(int consumedDoses) {
        this.consumedDoses = consumedDoses;
    }

    public CustomsDeclarationStatus getStatus() {
        return status;
    }

    public void setStatus(CustomsDeclarationStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
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
