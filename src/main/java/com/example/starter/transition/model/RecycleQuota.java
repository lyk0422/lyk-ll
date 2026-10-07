package com.example.starter.transition.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 回收额度：每个未改判正品的已登记过渡卷至多登记一次可回收公斤数；
 * 登记后只允许减少尚未消费的额度，复议改判正品时未消费额度同步失效。
 */
public class RecycleQuota {

    /** 额度状态：有效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 额度状态：已失效（复议改判正品）。 */
    public static final String STATUS_INVALIDATED = "INVALIDATED";
    /** 失效原因：复议改判正品。 */
    public static final String REASON_REEXAM_UPGRADED = "REEXAM_UPGRADED";

    private Long id;
    private String rollNo;
    /** 登记可回收公斤数（原额度），单位 kg。 */
    private BigDecimal registeredKg;
    /** 当前额度（含已消费部分），单位 kg。 */
    private BigDecimal currentKg;
    /** 累计已消费公斤数，单位 kg，只增不减。 */
    private BigDecimal consumedKg;
    /** 额度版本号：1 起，每次成功调减/消费/失效加 1。 */
    private int version;
    /** 额度状态：ACTIVE / INVALIDATED。 */
    private String status;
    /** 失效原因，NULL=未失效。 */
    private String invalidatedReason;
    private String requestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public BigDecimal getRegisteredKg() {
        return registeredKg;
    }

    public void setRegisteredKg(BigDecimal registeredKg) {
        this.registeredKg = registeredKg;
    }

    public BigDecimal getCurrentKg() {
        return currentKg;
    }

    public void setCurrentKg(BigDecimal currentKg) {
        this.currentKg = currentKg;
    }

    public BigDecimal getConsumedKg() {
        return consumedKg;
    }

    public void setConsumedKg(BigDecimal consumedKg) {
        this.consumedKg = consumedKg;
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

    public String getInvalidatedReason() {
        return invalidatedReason;
    }

    public void setInvalidatedReason(String invalidatedReason) {
        this.invalidatedReason = invalidatedReason;
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
}
