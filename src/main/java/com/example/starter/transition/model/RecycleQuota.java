package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回收重量登记（投料额度）：每个未改判正品的已登记过渡卷至多一条。
 *
 * <p>勾稽不变式：registered_kg = consumed_kg + 可用额度；
 * 改判正品失效时 invalidated_kg = 失效前未消费额度，已消费凭据保留。</p>
 */
public class RecycleQuota {

    /** 状态：有效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：改判正品后未消费额度已失效。 */
    public static final String STATUS_INVALIDATED = "INVALIDATED";
    /** 失效原因：复议改判正品。 */
    public static final String REASON_REEXAM_UPGRADED = "REEXAM_UPGRADED";

    private Long id;
    private String rollNo;
    /** 登记时原额度，单位 kg，登记后不再变化。 */
    private BigDecimal initialKg;
    /** 当前有效登记额度，单位 kg，只允许调减且不得小于累计消费。 */
    private BigDecimal registeredKg;
    /** 累计已消费公斤数，单位 kg。 */
    private BigDecimal consumedKg;
    /** 改判正品时失效的未消费额度，单位 kg，未失效为 0。 */
    private BigDecimal invalidatedKg;
    /** 额度版本号：登记为 1，每次成功调减/消费/失效 +1。 */
    private int version;
    /** 状态：ACTIVE / INVALIDATED。 */
    private String status;
    /** 失效原因：REEXAM_UPGRADED=复议改判正品，NULL=未失效。 */
    private String invalidReason;
    private String requestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime invalidatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRollNo() {
        return rollNo;
    }

    public void setRollNo(String rollNo) {
        this.rollNo = rollNo;
    }

    public BigDecimal getInitialKg() {
        return initialKg;
    }

    public void setInitialKg(BigDecimal initialKg) {
        this.initialKg = initialKg;
    }

    public BigDecimal getRegisteredKg() {
        return registeredKg;
    }

    public void setRegisteredKg(BigDecimal registeredKg) {
        this.registeredKg = registeredKg;
    }

    public BigDecimal getConsumedKg() {
        return consumedKg;
    }

    public void setConsumedKg(BigDecimal consumedKg) {
        this.consumedKg = consumedKg;
    }

    public BigDecimal getInvalidatedKg() {
        return invalidatedKg;
    }

    public void setInvalidatedKg(BigDecimal invalidatedKg) {
        this.invalidatedKg = invalidatedKg;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    public void setInvalidReason(String invalidReason) {
        this.invalidReason = invalidReason;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(LocalDateTime invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }
}
