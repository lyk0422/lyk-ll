package com.example.starter.domain;

import java.time.Instant;

/**
 * 成分血库存单位。一袋成分血以唯一条码入库，初始为 {@link UnitStatus#PENDING}；
 * 所有时刻均为 UTC 时刻。
 */
public class BloodUnit {

    private Long id;
    /** 唯一条码。 */
    private String barcode;
    /** 成分类型。 */
    private ComponentType componentType;
    /** 当前状态。 */
    private UnitStatus status;
    /** 初检结果，null 表示未回传。 */
    private TestResultCode initialResult;
    /** 复检结果，null 表示未回传。 */
    private TestResultCode repeatResult;
    /** 初检回传时刻(UTC)。 */
    private Instant initialReceivedAt;
    /** 复检回传时刻(UTC)。 */
    private Instant repeatReceivedAt;
    /** 效期边界时刻(UTC)：该时刻(含)之前可放行，之后过期。 */
    private Instant expiresAt;
    /** 过期判定时刻(UTC)。 */
    private Instant expiredAt;
    /** 报废原因。 */
    private String scrapReason;
    private Instant createdAt;
    private Instant updatedAt;
    /** 乐观锁版本号。 */
    private int version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public void setComponentType(ComponentType componentType) {
        this.componentType = componentType;
    }

    public UnitStatus getStatus() {
        return status;
    }

    public void setStatus(UnitStatus status) {
        this.status = status;
    }

    public TestResultCode getInitialResult() {
        return initialResult;
    }

    public void setInitialResult(TestResultCode initialResult) {
        this.initialResult = initialResult;
    }

    public TestResultCode getRepeatResult() {
        return repeatResult;
    }

    public void setRepeatResult(TestResultCode repeatResult) {
        this.repeatResult = repeatResult;
    }

    public Instant getInitialReceivedAt() {
        return initialReceivedAt;
    }

    public void setInitialReceivedAt(Instant initialReceivedAt) {
        this.initialReceivedAt = initialReceivedAt;
    }

    public Instant getRepeatReceivedAt() {
        return repeatReceivedAt;
    }

    public void setRepeatReceivedAt(Instant repeatReceivedAt) {
        this.repeatReceivedAt = repeatReceivedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public void setExpiredAt(Instant expiredAt) {
        this.expiredAt = expiredAt;
    }

    public String getScrapReason() {
        return scrapReason;
    }

    public void setScrapReason(String scrapReason) {
        this.scrapReason = scrapReason;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
