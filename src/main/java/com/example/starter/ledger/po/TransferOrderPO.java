package com.example.starter.ledger.po;

import java.time.Instant;

/**
 * 站间调拨单 PO。调出方发起、调入方签收，在途超时转异常，异常人工核销且仅一次。
 */
public class TransferOrderPO {

    /** 主键。 */
    private Long id;
    /** 调拨单号。 */
    private String orderNo;
    /** 调拨电池编码。 */
    private String batteryCode;
    /** 调出站点编码（发起方）。 */
    private String fromStation;
    /** 调入站点编码（签收方）。 */
    private String toStation;
    /** 调拨状态，取值见 {@link com.example.starter.ledger.domain.TransferStatus}。 */
    private String status;
    /** 调出时间（UTC），在途计时起点。 */
    private Instant dispatchedAt;
    /** 签收时间（UTC）；空值表示尚未签收。 */
    private Instant receivedAt;
    /** 超时转异常时间（UTC）；空值表示正常在途或已签收。 */
    private Instant exceptionAt;
    /** 人工核销时间（UTC）；空值表示未核销。 */
    private Instant writtenOffAt;
    /** 核销原因；空值表示未核销。 */
    private String writeoffReason;
    /** 乐观锁版本号。 */
    private long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getBatteryCode() {
        return batteryCode;
    }

    public void setBatteryCode(String batteryCode) {
        this.batteryCode = batteryCode;
    }

    public String getFromStation() {
        return fromStation;
    }

    public void setFromStation(String fromStation) {
        this.fromStation = fromStation;
    }

    public String getToStation() {
        return toStation;
    }

    public void setToStation(String toStation) {
        this.toStation = toStation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getExceptionAt() {
        return exceptionAt;
    }

    public void setExceptionAt(Instant exceptionAt) {
        this.exceptionAt = exceptionAt;
    }

    public Instant getWrittenOffAt() {
        return writtenOffAt;
    }

    public void setWrittenOffAt(Instant writtenOffAt) {
        this.writtenOffAt = writtenOffAt;
    }

    public String getWriteoffReason() {
        return writeoffReason;
    }

    public void setWriteoffReason(String writeoffReason) {
        this.writeoffReason = writeoffReason;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
